#!/usr/bin/env bash
# 16-mcp-dns-sync.sh — Adds bflow-mcp to the SAME Cloudflare dynamic-DNS
# mechanism as bflow-backend (see infra/bootstrap/15-dns-sync.sh), instead
# of building a second one from scratch.
#
# Why a second Lambda, not a second EventBridge rule:
#   The existing rule (`${PROJECT_NAME}-ecs-task-state-change`) already
#   matches "any RUNNING task in this cluster" — it doesn't filter by
#   service. The existing Lambda (infra/dns-sync) already filters by
#   `detail.group == "service:" + ECS_SERVICE_NAME` internally, so a
#   bflow-mcp task starting up is already silently ignored by it today —
#   nothing to fix there.
#
#   This script builds the SAME Lambda source (infra/dns-sync, a copy of
#   BFlow-Financial-Engine's, same module logic) as a SECOND deployed
#   function, pointed at bflow-mcp via its own ECS_SERVICE_NAME/
#   CLOUDFLARE_SECRET_ARN env vars, and adds it as a second target on
#   the SAME existing rule. No new Go logic — just built and deployed
#   from this repo instead of assuming the monolith's build artifact.
#
# Requires infra/mcp-cloudflare.env (same shape as cloudflare.env, but
# for the mcp.bflow-studio.com record) — skips with instructions if
# missing, same convention as 15-dns-sync.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source "$SCRIPT_DIR/config.env"
source "$SCRIPT_DIR/outputs.env"
source "$SCRIPT_DIR/lib/helpers.sh"

OUTPUT_FILE="$SCRIPT_DIR/outputs.env"

MCP_ECS_SERVICE_NAME="bflow-mcp"
MCP_LAMBDA_ROLE_NAME="${PROJECT_NAME}-mcp-dns-sync-lambda-role"
MCP_LAMBDA_FUNCTION_NAME="${PROJECT_NAME}-mcp-dns-sync"
EVENTBRIDGE_RULE_NAME="bflow-ecs-task-state-change" # shared rule, lives in BFlow-Financial-Engine's infra, unchanged
MCP_CLOUDFLARE_SECRET_NAME="${PROJECT_NAME}/mcp-cloudflare-dns"
LAMBDA_SOURCE_DIR="$SCRIPT_DIR/dns-sync" # copied from BFlow-Financial-Engine/infra/dns-sync
LAMBDA_BUILD_DIR="$LAMBDA_SOURCE_DIR/.build"
LAMBDA_ZIP="$LAMBDA_BUILD_DIR/lambda_function.zip"

if [[ ! -f "$SCRIPT_DIR/mcp-cloudflare.env" ]]; then
    echo "infra/mcp-cloudflare.env not found - skipping MCP DNS sync setup."
    echo "Copy infra/mcp-cloudflare.env.example to infra/mcp-cloudflare.env," \
         "point it at the mcp.bflow-studio.com record, and re-run."
    exit 0
fi

source "$SCRIPT_DIR/mcp-cloudflare.env"

for VAR in CLOUDFLARE_API_TOKEN CLOUDFLARE_ZONE_ID CLOUDFLARE_DNS_RECORD_ID CLOUDFLARE_DNS_RECORD_NAME; do
    if [[ -z "${!VAR:-}" ]]; then
        echo "Missing $VAR in infra/mcp-cloudflare.env."
        exit 1
    fi
done

: "${ECS_CLUSTER_ARN:?ECS_CLUSTER_ARN must be set in infra/config.env (copy it from BFlow-Financial-Engine/infra/outputs.env)}"

create_or_update_mcp_secret() {
    local SECRET_ARN
    local SECRET_VALUE

    SECRET_VALUE=$(jq -n \
        --arg token "$CLOUDFLARE_API_TOKEN" \
        --arg zone "$CLOUDFLARE_ZONE_ID" \
        --arg record "$CLOUDFLARE_DNS_RECORD_ID" \
        --arg name "$CLOUDFLARE_DNS_RECORD_NAME" \
    '{
        "CLOUDFLARE_API_TOKEN": $token,
        "CLOUDFLARE_ZONE_ID": $zone,
        "CLOUDFLARE_DNS_RECORD_ID": $record,
        "CLOUDFLARE_DNS_RECORD_NAME": $name
    }')

    if aws secretsmanager describe-secret \
        --region "$AWS_REGION" \
        --secret-id "$MCP_CLOUDFLARE_SECRET_NAME" >/dev/null 2>&1; then

        aws secretsmanager put-secret-value \
            --region "$AWS_REGION" \
            --secret-id "$MCP_CLOUDFLARE_SECRET_NAME" \
            --secret-string "$SECRET_VALUE" \
            >/dev/null

        SECRET_ARN=$(aws secretsmanager describe-secret \
            --region "$AWS_REGION" \
            --secret-id "$MCP_CLOUDFLARE_SECRET_NAME" \
            --query ARN --output text)
    else
        SECRET_ARN=$(aws secretsmanager create-secret \
            --region "$AWS_REGION" \
            --name "$MCP_CLOUDFLARE_SECRET_NAME" \
            --description "Cloudflare DNS credentials for bflow-mcp dns-sync" \
            --secret-string "$SECRET_VALUE" \
            --tags \
                Key=Project,Value="$PROJECT_NAME" \
                Key=Environment,Value="$ENVIRONMENT" \
                Key=ManagedBy,Value="$MANAGED_BY" \
            --query "ARN" --output text)
    fi

    append_output "MCP_CLOUDFLARE_SECRET_ARN" "$SECRET_ARN"
    echo "$SECRET_ARN"
}

create_mcp_lambda_role() {
    local SECRET_ARN="$1"
    local ROLE_ARN

    ROLE_ARN=$(aws iam get-role \
        --role-name "$MCP_LAMBDA_ROLE_NAME" \
        --query "Role.Arn" --output text 2>/dev/null || true)

    if [[ -z "$ROLE_ARN" || "$ROLE_ARN" == "None" ]]; then
        ROLE_ARN=$(aws iam create-role \
            --role-name "$MCP_LAMBDA_ROLE_NAME" \
            --assume-role-policy-document '{
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"Service": "lambda.amazonaws.com"},
                    "Action": "sts:AssumeRole"
                }]
            }' \
            --tags \
                Key=Project,Value="$PROJECT_NAME" \
                Key=Environment,Value="$ENVIRONMENT" \
                Key=ManagedBy,Value="$MANAGED_BY" \
            --query "Role.Arn" --output text)
        sleep 8
    fi

    aws iam attach-role-policy \
        --role-name "$MCP_LAMBDA_ROLE_NAME" \
        --policy-arn "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole" \
        2>/dev/null || true

    aws iam put-role-policy \
        --role-name "$MCP_LAMBDA_ROLE_NAME" \
        --policy-name "${PROJECT_NAME}-mcp-dns-sync-permissions" \
        --policy-document "{
            \"Version\": \"2012-10-17\",
            \"Statement\": [
                {
                    \"Sid\": \"DescribeEcsAndEni\",
                    \"Effect\": \"Allow\",
                    \"Action\": [
                        \"ecs:DescribeTasks\",
                        \"ecs:ListTasks\",
                        \"ec2:DescribeNetworkInterfaces\"
                    ],
                    \"Resource\": \"*\"
                },
                {
                    \"Sid\": \"ReadCloudflareSecret\",
                    \"Effect\": \"Allow\",
                    \"Action\": \"secretsmanager:GetSecretValue\",
                    \"Resource\": \"${SECRET_ARN}\"
                }
            ]
        }"

    append_output "MCP_DNS_SYNC_LAMBDA_ROLE_ARN" "$ROLE_ARN"
    echo "$ROLE_ARN"
}

package_lambda() {
    if ! command -v go >/dev/null 2>&1; then
        echo "Go is not installed — needed to build infra/dns-sync." >&2
        exit 1
    fi

    mkdir -p "$LAMBDA_BUILD_DIR"

    (
        cd "$LAMBDA_SOURCE_DIR"
        CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
            go build -trimpath -ldflags="-s -w" -o "$LAMBDA_BUILD_DIR/bootstrap" .
    )

    (cd "$LAMBDA_BUILD_DIR" && zip -q "$LAMBDA_ZIP" bootstrap)

    echo "Built $LAMBDA_ZIP" >&2
}

deploy_mcp_lambda() {
    local ROLE_ARN="$1"
    local SECRET_ARN="$2"
    local FUNCTION_ARN
    local ENV_JSON

    package_lambda

    ENV_JSON="{
        \"Variables\": {
            \"ECS_CLUSTER_NAME\": \"$ECS_CLUSTER_NAME\",
            \"ECS_SERVICE_NAME\": \"$MCP_ECS_SERVICE_NAME\",
            \"CLOUDFLARE_SECRET_ARN\": \"$SECRET_ARN\"
        }
    }"

    if aws lambda get-function \
        --region "$AWS_REGION" \
        --function-name "$MCP_LAMBDA_FUNCTION_NAME" >/dev/null 2>&1; then

        aws lambda update-function-code \
            --region "$AWS_REGION" \
            --function-name "$MCP_LAMBDA_FUNCTION_NAME" \
            --zip-file "fileb://$LAMBDA_ZIP" >/dev/null

        aws lambda wait function-updated \
            --region "$AWS_REGION" --function-name "$MCP_LAMBDA_FUNCTION_NAME"

        aws lambda update-function-configuration \
            --region "$AWS_REGION" \
            --function-name "$MCP_LAMBDA_FUNCTION_NAME" \
            --role "$ROLE_ARN" \
            --timeout 60 \
            --environment "$ENV_JSON" >/dev/null

        aws lambda wait function-updated \
            --region "$AWS_REGION" --function-name "$MCP_LAMBDA_FUNCTION_NAME"
    else
        aws lambda create-function \
            --region "$AWS_REGION" \
            --function-name "$MCP_LAMBDA_FUNCTION_NAME" \
            --runtime provided.al2023 \
            --architectures arm64 \
            --handler bootstrap \
            --role "$ROLE_ARN" \
            --timeout 60 \
            --memory-size 128 \
            --zip-file "fileb://$LAMBDA_ZIP" \
            --environment "$ENV_JSON" \
            --tags \
                Project="$PROJECT_NAME",Environment="$ENVIRONMENT",ManagedBy="$MANAGED_BY" \
            >/dev/null

        aws lambda wait function-active \
            --region "$AWS_REGION" --function-name "$MCP_LAMBDA_FUNCTION_NAME"
    fi

    FUNCTION_ARN=$(aws lambda get-function \
        --region "$AWS_REGION" \
        --function-name "$MCP_LAMBDA_FUNCTION_NAME" \
        --query "Configuration.FunctionArn" --output text)

    append_output "MCP_DNS_SYNC_LAMBDA_ARN" "$FUNCTION_ARN"
    echo "$FUNCTION_ARN"
}

add_as_second_target_on_shared_rule() {
    local FUNCTION_ARN="$1"
    local RULE_ARN

    RULE_ARN=$(aws events describe-rule \
        --region "$AWS_REGION" \
        --name "$EVENTBRIDGE_RULE_NAME" \
        --query "Arn" --output text)

    aws lambda add-permission \
        --region "$AWS_REGION" \
        --function-name "$MCP_LAMBDA_FUNCTION_NAME" \
        --statement-id "AllowEventBridgeInvoke" \
        --action "lambda:InvokeFunction" \
        --principal "events.amazonaws.com" \
        --source-arn "$RULE_ARN" \
        >/dev/null 2>&1 || echo "Lambda permission already granted." >&2

    # Id=2: the existing rule already has bflow-backend's Lambda as
    # Id=1 (see 15-dns-sync.sh). put-targets is additive/idempotent per
    # Id, so this does not disturb the existing target.
    aws events put-targets \
        --region "$AWS_REGION" \
        --rule "$EVENTBRIDGE_RULE_NAME" \
        --targets "Id=2,Arn=$FUNCTION_ARN" \
        >/dev/null
}

SECRET_ARN=$(create_or_update_mcp_secret)
ROLE_ARN=$(create_mcp_lambda_role "$SECRET_ARN")
FUNCTION_ARN=$(deploy_mcp_lambda "$ROLE_ARN" "$SECRET_ARN")
add_as_second_target_on_shared_rule "$FUNCTION_ARN"

echo "MCP DNS sync ready."
echo "Any RUNNING task for '${MCP_ECS_SERVICE_NAME}' will now update" \
     "${CLOUDFLARE_DNS_RECORD_NAME} automatically."