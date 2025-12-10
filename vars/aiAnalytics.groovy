def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  def TMP_DIR = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
  sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

  stage('Clone Repos') {
    dir(TMP_DIR) {
      sh """
        git clone '${terraformRepo}' terraform-code
        git clone 'https://github.com/shakilmunavary/jenkins-shared-ai-lib.git' jenkins-shared-ai-lib
      """
    }
  }

  stage('Terraform Init & Plan') {
    dir("${TMP_DIR}/terraform-code/${folderName}") {
      sh """
        terraform init
        terraform plan -out=tfplan.binary
        terraform show -json tfplan.binary > tfplan.raw.json

        jq '
          .resource_changes |= sort_by(.address) |
          del(.resource_changes[].change.after_unknown) |
          del(.resource_changes[].change.before_sensitive) |
          del(.resource_changes[].change.after_sensitive) |
          del(.resource_changes[].change.after_identity) |
          del(.resource_changes[].change.before) |
          del(.resource_changes[].change.after.tags_all) |
          del(.resource_changes[].change.after.tags) |
          del(.resource_changes[].change.after.id) |
          del(.resource_changes[].change.after.arn)
        ' tfplan.raw.json > tfplan.json
      """
    }
  }

  stage('Generate Resource × Rule Matrix') {
    dir("${TMP_DIR}/terraform-code/${folderName}") {
      // Use single-quoted Groovy string to avoid Groovy interpolation of $ and special chars
      sh '''
        set -euo pipefail

        PLAN=tfplan.json
        GUARDRAILS=$WORKSPACE/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt
        MATRIX=resource_rule_matrix.txt
        : > "$MATRIX"

        # Collect resource addresses (type.name)
        RESOURCES=$(jq -r ".resource_changes[].address" "$PLAN")

        # Optional: build a map of type -> count (for debugging/validation)
        echo "Resources detected:"
        echo "$RESOURCES" | sed "s/^/  - /"

        for RES in $RESOURCES; do
          TYPE=$(echo "$RES" | cut -d"." -f1)

          # Find rule header lines for this resource type block, then the next rule line following it.
          # This assumes guardrails file sections like:
          #   Resource Type: aws_instance
          #   [Rule ID: EC2-001] ...
          #   Rule: Must use approved AMIs
          #
          # We select lines starting with "[" using ^[[] which matches literal '[' safely.
          awk -v type="$TYPE" '
            $0 ~ "^Resource Type:[[:space:]]*"type"$" { inType=1; next }
            /^Resource Type:/ { inType=0 }                # leave the section if next type begins
            inType && /^[[]/ { print; next }              # print rule header lines starting with [
          ' "$GUARDRAILS" | while read -r RULELINE; do
            # Extract RULE ID from header line
            RULEID=$(echo "$RULELINE" | sed -n "s/.*Rule ID:[[:space:]]*\\([^]]*\\)].*/\\1/p")

            # Get the rule description: the first 'Rule:' line following the header
            RULEDESC=$(awk -v hdr="$RULELINE" '
              BEGIN {found=0}
              $0 == hdr {found=1; next}
              found && /^Rule:/ { sub(/^Rule:[[:space:]]*/, "", $0); print; exit }
            ' "$GUARDRAILS")

            # Fallback if no Rule: line found
            if [ -z "$RULEDESC" ]; then
              RULEDESC="Rule description not found"
            fi

            # Append matrix row: resource address, rule id, rule desc
            printf "%s\t%s\t%s\n" "$RES" "$RULEID" "$RULEDESC" >> "$MATRIX"
          done
        done

        echo "Matrix generated at: $MATRIX"
        wc -l "$MATRIX" || true
      '''
    }
  }

  stage('AI analytics with Azure OpenAI') {
    withCredentials([
      string(credentialsId: 'AZURE_API_KEY',         variable: 'AZURE_API_KEY'),
      string(credentialsId: 'AZURE_API_BASE',        variable: 'AZURE_API_BASE'),
      string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
      string(credentialsId: 'AZURE_API_VERSION',     variable: 'AZURE_API_VERSION')
    ]) {
      def tfDir          = "${TMP_DIR}/terraform-code/${folderName}"
      def sharedLibDir   = "${TMP_DIR}/jenkins-shared-ai-lib"
      def tfPlanJsonPath = "${tfDir}/tfplan.json"
      def guardrailsPath = "${sharedLibDir}/guardrails/guardrails_v1.txt"
      def templatePath   = "${sharedLibDir}/reference_terra_analysis_html.html"
      def matrixPath     = "${tfDir}/resource_rule_matrix.txt"
      def outputHtmlPath = "${tfDir}/output.html"
      def payloadPath    = "${tfDir}/payload.json"
      def responsePath   = "${outputHtmlPath}.raw"

      // Use triple double quotes here; escape ${...} with backslash to avoid Groovy interpolation.
      sh """
        set -euo pipefail

        PLAN_FILE_CONTENT=\$(jq -Rs . < "${tfPlanJsonPath}")
        GUARDRAILS_CONTENT=\$(jq -Rs . < "${guardrailsPath}")
        SAMPLE_HTML=\$(jq -Rs . < "${templatePath}")
        MATRIX_CONTENT=\$(jq -Rs . < "${matrixPath}")

        cat <<EOF > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
      "content":  "
You are a Terraform compliance auditor. You will receive three input files:
1) Terraform Plan in JSON format,
2) Guardrails Checklist (versioned),
3) Sample HTML Template.

Your task is to analyze the Terraform plan against the guardrails and return a single HTML output with the following sections:

1️⃣ Change Summary Table
- Title: 'What's Being Changed'
- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details
- Ensure resource count matches Terraform plan

2️⃣ Terraform Code Recommendations
- Actionable suggestions to improve code quality

3️⃣ Security and Compliance Recommendations
- Highlight misconfigurations and generic recommendations

4️⃣ Guardrail Compliance Summary
- Title: 'Guardrail Compliance Summary'
- Columns: Terraform Resource, Rule Id, Rule, Status (PASS or FAIL)
- For each resource type present in the Terraform plan, evaluate all rules defined for that type in the Guardrails Checklist File attached.
- Output one row per (Terraform Resource, Rule ID). Do not skip any rule for a resource type that exists in the plan.
- Ensure the number of rows equals (#rules defined for that resource type × #resources of that type in the plan).
- At the end, calculate Overall Guardrail Coverage % = (PASS / total rules evaluated) × 100.

5️⃣ Overall Status
- Status: PASS if coverage ≥ 90%, else FAIL

6️⃣ HTML Formatting
- Match visual structure of sample HTML attached using semantic tags and inline styles
"
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": \${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Resource × Rule Matrix:\\n" },
    { "role": "user", "content": \${MATRIX_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        if jq -e '.choices[0].message.content' "${responsePath}" > /dev/null; then
          jq -r '.choices[0].message.content' "${responsePath}" > "${outputHtmlPath}"
        else
          echo "<html><body><h2>⚠️ AI response was empty or malformed</h2></body></html>" > "${outputHtmlPath}"
        fi
      """
    }
  }

  stage('Publish Report') {
    publishHTML([
      reportName: 'AI Analysis',
      reportDir: "${TMP_DIR}/terraform-code/${folderName}",
      reportFiles: 'output.html',
      keepAll: true,
      allowMissing: false,
      alwaysLinkToLastBuild: true
    ])
  }

  stage('Evaluate Guardrail Coverage') {
    def outputHtml = "${TMP_DIR}/terraform-code/${folderName}/output.html"
    def passCount  = sh(script: "grep -oi 'class=\"pass\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
    def failCount  = sh(script: "grep -oi 'class=\"fail\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
    def coverage   = (passCount + failCount) > 0 ? (passCount * 100 / (passCount + failCount)).toInteger() : 0

    echo "🔍 Guardrail Coverage: ${coverage}%"
    sh "sed -i 's/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: ${coverage}%/' '${outputHtml}'"

    env.PIPELINE_DECISION     = coverage >= 50 ? 'APPROVED' : 'REJECTED'
    currentBuild.description  = "Auto-${env.PIPELINE_DECISION.toLowerCase()} (Coverage: ${coverage}%)"
  }

  stage('Decision') {
    if (env.PIPELINE_DECISION == 'APPROVED') {
      echo "✅ Pipeline approved. Proceeding..."
    } else {
      echo "❌ Pipeline rejected. Halting..."
    }
  }
}
