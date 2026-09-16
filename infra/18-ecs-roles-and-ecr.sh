#!/usr/bin/env bash
# 18-ecs-roles-and-ecr.sh — Creates the ECR repository and the two ECS
# roles bflow-mcp's task definition needs. Idempotent, same pattern as
# BFlow-Financial-Engine's 06-ecr.sh + 09-iam.sh.
#
# The task role intentionally gets ZERO permissions attached (ADR-0010
# §1): bflow-mcp never calls an AWS service directly — no S3, no
# Secrets Manager, nothing. It exists only because ECS requires a task
# role to be specified. If a future tool genuinely needs an AWS
# permission, that's a decision to make explicitly then, not a default
# to reach for now.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source "$SCRIPT_DIR/config.env"
source "$SCRIPT_DIR/outputs.env"
source "$SCRIPT_DIR/lib/helpers.sh"

OUTPUT_FILE="$SCRIPT_DIR/outputs.env"

ECR_REPOSITORY="bflow-mcp"
ECS_TASK_EXECUTION_ROLE_NAME="bflow-mcp-ecs-execution-role"
ECS_TASK_ROLE_NAME="bflow-mcp-ecs-task-role"

create_ecr_repository() {
    local DESCRIBE_OUTPUT=""
    local REPOSITORY_URI
    local REPOSITORY_ARN

    if DESCRIBE_OUTPUT=$(aws ecr describe-repositories \
        --region "$AWS_REGION" \
        --repository-names "$ECR_REPOSITORY" \
        --query "repositories[0].[repositoryUri,repositoryArn]" \
        --output text 2>&1); then

        read -r REPOSITORY_URI REPOSITORY_ARN <<<"$DESCRIBE_OUTPUT"
        echo "ECR repository already exists."
    else
        if echo "$DESCRIBE_OUTPUT" | grep -q "RepositoryNotFoundException"; then
            echo "Creating ECR repository..."

            read -r REPOSITORY_URI REPOSITORY_ARN <<<"$(
                aws ecr create-repository \
                    --region "$AWS_REGION" \
                    --repository-name "$ECR_REPOSITORY" \
                    --image-scanning-configuration scanOnPush=true \
                    --image-tag-mutability IMMUTABLE \
                    --encryption-configuration encryptionType=AES256 \
                    --query "repository.[repositoryUri,repositoryArn]" \
                    --output text
            )"
        else
            echo "Failed to check ECR repository."
            echo "$DESCRIBE_OUTPUT"
            exit 1
        fi
    fi

    aws ecr tag-resource \
        --region "$AWS_REGION" \
        --resource-arn "$REPOSITORY_ARN" \
        --tags \
            Key=Project,Value="$PROJECT_NAME" \
            Key=Environment,Value="$ENVIRONMENT" \
            Key=ManagedBy,Value="$MANAGED_BY"

    append_output "ECR_REPOSITORY_URI" "$REPOSITORY_URI"
    append_output "ECR_REPOSITORY_ARN" "$REPOSITORY_ARN"
    echo "ECR ready: $REPOSITORY_URI"
}

create_role() {
    local ROLE_NAME="$1"
    local POLICY_DOCUMENT="$2"
    local OUTPUT_KEY="$3"
    local ROLE_ARN

    ROLE_ARN=$(aws iam get-role \
        --role-name "$ROLE_NAME" \
        --query "Role.Arn" \
        --output text 2>/dev/null || true)

    if [[ -z "$ROLE_ARN" || "$ROLE_ARN" == "None" ]]; then
        echo "Creating role: $ROLE_NAME"
        ROLE_ARN=$(aws iam create-role \
            --role-name "$ROLE_NAME" \
            --assume-role-policy-document "$POLICY_DOCUMENT" \
            --tags \
                Key=Project,Value="$PROJECT_NAME" \
                Key=Environment,Value="$ENVIRONMENT" \
                Key=ManagedBy,Value="$MANAGED_BY" \
            --query "Role.Arn" \
            --output text)
    else
        echo "Role already exists: $ROLE_NAME"
    fi

    append_output "$OUTPUT_KEY" "$ROLE_ARN"
}

attach_managed_policy() {
    local ROLE_NAME="$1"
    local POLICY_ARN="$2"
    local ATTACHED

    ATTACHED=$(aws iam list-attached-role-policies \
        --role-name "$ROLE_NAME" \
        --query "AttachedPolicies[?PolicyArn=='${POLICY_ARN}'] | length(@)" \
        --output text)

    if [[ "$ATTACHED" == "0" ]]; then
        aws iam attach-role-policy \
            --role-name "$ROLE_NAME" \
            --policy-arn "$POLICY_ARN"
    fi
}

ECS_TASKS_TRUST_POLICY='{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {"Service": "ecs-tasks.amazonaws.com"},
            "Action": "sts:AssumeRole"
        }
    ]
}'

create_ecr_repository

echo "Creating ECS execution role..."
create_role \
    "$ECS_TASK_EXECUTION_ROLE_NAME" \
    "$ECS_TASKS_TRUST_POLICY" \
    "ECS_MCP_EXECUTION_ROLE_ARN"

attach_managed_policy \
    "$ECS_TASK_EXECUTION_ROLE_NAME" \
    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"

echo "Creating ECS task role (no permissions attached — ADR-0010 §1)..."
create_role \
    "$ECS_TASK_ROLE_NAME" \
    "$ECS_TASKS_TRUST_POLICY" \
    "ECS_MCP_TASK_ROLE_ARN"

CLOUDWATCH_LOG_GROUP="/ecs/bflow-mcp"

echo "Creating CloudWatch log group..."
if aws logs describe-log-groups \
    --region "$AWS_REGION" \
    --log-group-name-prefix "$CLOUDWATCH_LOG_GROUP" \
    --query "logGroups[?logGroupName=='${CLOUDWATCH_LOG_GROUP}'] | length(@)" \
    --output text | grep -q "1"; then
    echo "Log group already exists."
else
    aws logs create-log-group \
        --region "$AWS_REGION" \
        --log-group-name "$CLOUDWATCH_LOG_GROUP"

    aws logs tag-log-group \
        --region "$AWS_REGION" \
        --log-group-name "$CLOUDWATCH_LOG_GROUP" \
        --tags "Project=${PROJECT_NAME},Environment=${ENVIRONMENT},ManagedBy=${MANAGED_BY}"
fi

aws logs put-retention-policy \
    --region "$AWS_REGION" \
    --log-group-name "$CLOUDWATCH_LOG_GROUP" \
    --retention-in-days 14

append_output "CLOUDWATCH_MCP_LOG_GROUP" "$CLOUDWATCH_LOG_GROUP"

echo ""
echo "Done. ECS_MCP_EXECUTION_ROLE_ARN, ECS_MCP_TASK_ROLE_ARN, and"
echo "CLOUDWATCH_MCP_LOG_GROUP are now in infra/outputs.env — GitHub"
echo "Actions repo Variables need these for the task definition template."
