#!/usr/bin/env bash
# 19-github-oidc.sh — Creates a GitHub Actions OIDC role dedicated to
# bflow-mcp: SEPARATE from BFlow-Financial-Engine's deploy role
# (ADR-0010's blast-radius principle — this repo's CI/CD credentials
# can't touch the monolith's resources, and vice versa).
#
# Reuses the AWS account's existing GitHub OIDC PROVIDER if
# BFlow-Financial-Engine already created one (IAM OIDC providers are
# account-wide, not per-repo — creating a second one for the same URL
# would just fail). Only the ROLE and its policy are new.
#
# Requires 18-ecs-roles-and-ecr.sh to have run first (needs its
# outputs: ECR_REPOSITORY_ARN, ECS_MCP_EXECUTION_ROLE_ARN,
# ECS_MCP_TASK_ROLE_ARN).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source "$SCRIPT_DIR/config.env"
source "$SCRIPT_DIR/outputs.env"
source "$SCRIPT_DIR/lib/helpers.sh"

OUTPUT_FILE="$SCRIPT_DIR/outputs.env"

OIDC_PROVIDER_URL="token.actions.githubusercontent.com"
ROLE_NAME="bflow-mcp-github-actions-role"
GITHUB_OWNER_ID="174159480"
GITHUB_REPOSITORY_ID="1368671239"

ECR_REPOSITORY_ARN=$(require_output ECR_REPOSITORY_ARN)
ECS_MCP_EXECUTION_ROLE_ARN=$(require_output ECS_MCP_EXECUTION_ROLE_ARN)
ECS_MCP_TASK_ROLE_ARN=$(require_output ECS_MCP_TASK_ROLE_ARN)

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

create_or_reuse_oidc_provider() {
    local PROVIDER_ARN

    PROVIDER_ARN=$(aws iam list-open-id-connect-providers \
        --query "OpenIDConnectProviderList[?contains(Arn, '${OIDC_PROVIDER_URL}')].Arn" \
        --output text)

    if [[ -n "$PROVIDER_ARN" && "$PROVIDER_ARN" != "None" ]]; then
        echo "GitHub OIDC provider already exists (shared with BFlow-Financial-Engine if it created it)."
        append_output "GITHUB_OIDC_PROVIDER_ARN" "$PROVIDER_ARN"
        return
    fi

    echo "Creating GitHub OIDC provider..."
    PROVIDER_ARN=$(aws iam create-open-id-connect-provider \
        --url "https://${OIDC_PROVIDER_URL}" \
        --client-id-list "sts.amazonaws.com" \
        --thumbprint-list "6938fd4d98bab03faadb97b34396831e3780aea1" \
        --query "OpenIDConnectProviderArn" \
        --output text)

    append_output "GITHUB_OIDC_PROVIDER_ARN" "$PROVIDER_ARN"
}

create_deploy_role() {
    local ROLE_ARN

    ROLE_ARN=$(aws iam get-role \
        --role-name "$ROLE_NAME" \
        --query "Role.Arn" \
        --output text 2>/dev/null || true)

    # Scoped to bflow-mcp's own GitHub repo, and only to jobs declaring
    # "environment: production" — same verified trust-policy shape as
    # BFlow-Financial-Engine's role, just a different repo in the sub claim.
    TRUST_POLICY=$(cat <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Federated": "arn:aws:iam::${ACCOUNT_ID}:oidc-provider/${OIDC_PROVIDER_URL}"
            },
            "Action": "sts:AssumeRoleWithWebIdentity",
            "Condition": {
                "StringEquals": {
                    "token.actions.githubusercontent.com:sub": "repo:${GITHUB_OWNER}@${GITHUB_OWNER_ID}/${GITHUB_REPOSITORY}@${GITHUB_REPOSITORY_ID}:environment:production",
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
                }
            }
        }
    ]
}
EOF
)

    if [[ -n "$ROLE_ARN" && "$ROLE_ARN" != "None" ]]; then
        echo "Role already exists. Updating trust policy..."
        aws iam update-assume-role-policy \
            --role-name "$ROLE_NAME" \
            --policy-document "$TRUST_POLICY"
    else
        echo "Creating role: $ROLE_NAME"
        ROLE_ARN=$(aws iam create-role \
            --role-name "$ROLE_NAME" \
            --assume-role-policy-document "$TRUST_POLICY" \
            --tags \
                Key=Project,Value="$PROJECT_NAME" \
                Key=Environment,Value="$ENVIRONMENT" \
                Key=ManagedBy,Value="$MANAGED_BY" \
            --query "Role.Arn" \
            --output text)
    fi

    append_output "GITHUB_ACTIONS_ROLE_ARN" "$ROLE_ARN"
}

create_inline_policy() {
    # Deliberately minimal vs. BFlow-Financial-Engine's deploy policy:
    # no Secrets Manager (bflow-mcp holds no secrets, ADR-0010 §2), no
    # S3/SQS/SNS/Textract (none of that exists here). Every Sid below
    # maps to something ci.yml or deploy.yml actually calls — nothing
    # speculative.
    POLICY=$(cat <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "ECRAuth",
            "Effect": "Allow",
            "Action": ["ecr:GetAuthorizationToken"],
            "Resource": "*"
        },
        {
            "Sid": "ECRPushAndValidate",
            "Effect": "Allow",
            "Action": [
                "ecr:BatchCheckLayerAvailability",
                "ecr:CompleteLayerUpload",
                "ecr:DescribeRepositories",
                "ecr:GetDownloadUrlForLayer",
                "ecr:InitiateLayerUpload",
                "ecr:PutImage",
                "ecr:UploadLayerPart"
            ],
            "Resource": "${ECR_REPOSITORY_ARN}"
        },
        {
            "Sid": "ECSDeploy",
            "Effect": "Allow",
            "Action": [
                "ecs:RegisterTaskDefinition",
                "ecs:DescribeTaskDefinition",
                "ecs:DescribeClusters",
                "ecs:DescribeServices",
                "ecs:CreateService",
                "ecs:UpdateService",
                "ecs:ListTasks",
                "ecs:DescribeTasks",
                "ecs:TagResource"
            ],
            "Resource": "*"
        },
        {
            "Sid": "EC2NetworkingValidate",
            "Effect": "Allow",
            "Action": [
                "ec2:DescribeSubnets",
                "ec2:DescribeSecurityGroups",
                "ec2:DescribeNetworkInterfaces"
            ],
            "Resource": "*"
        },
        {
            "Sid": "CloudWatchValidate",
            "Effect": "Allow",
            "Action": ["logs:DescribeLogGroups"],
            "Resource": "*"
        },
        {
            "Sid": "IAMValidateAndPass",
            "Effect": "Allow",
            "Action": ["iam:GetRole", "iam:PassRole"],
            "Resource": [
                "${ECS_MCP_EXECUTION_ROLE_ARN}",
                "${ECS_MCP_TASK_ROLE_ARN}"
            ]
        },
        {
            "Sid": "IAMSelfPermissionSimulation",
            "Effect": "Allow",
            "Action": [
                "iam:SimulatePrincipalPolicy",
                "iam:GetContextKeysForPrincipalPolicy"
            ],
            "Resource": "arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"
         }
    ]
}
EOF
)

    aws iam put-role-policy \
        --role-name "$ROLE_NAME" \
        --policy-name "bflow-mcp-github-deploy-policy" \
        --policy-document "$POLICY"
}

# Verifies (via IAM policy simulation, no real AWS calls made) that the
# role's final policy actually grants the specific actions deploy.yml's
# "validate-environment" and "deploy" jobs perform — not just that a
# policy was attached, but that it evaluates to Allow for each one.
verify_permissions() {
    local ROLE_ARN
    ROLE_ARN=$(require_output GITHUB_ACTIONS_ROLE_ARN)

    # action | resource ARN to simulate against | display label
    # Actions scoped to "Resource": "*" in the policy are tested against
    # "*". Actions scoped to a specific ARN must be simulated against
    # that same ARN — simulating them against "*" always returns
    # implicitDeny, even when the policy is correct (a specific ARN
    # never matches the resource "*").
    local CHECKS=(
        "ecr:GetAuthorizationToken|*|ecr:GetAuthorizationToken"
        "ecr:PutImage|${ECR_REPOSITORY_ARN}|ecr:PutImage"
        "ecs:RegisterTaskDefinition|*|ecs:RegisterTaskDefinition"
        "ecs:UpdateService|*|ecs:UpdateService"
        "ecs:CreateService|*|ecs:CreateService"
        "ec2:DescribeSubnets|*|ec2:DescribeSubnets"
        "logs:DescribeLogGroups|*|logs:DescribeLogGroups"
        "iam:PassRole|${ECS_MCP_EXECUTION_ROLE_ARN}|iam:PassRole (execution role)"
        "iam:PassRole|${ECS_MCP_TASK_ROLE_ARN}|iam:PassRole (task role)"
        "iam:SimulatePrincipalPolicy|${ROLE_ARN}|iam:SimulatePrincipalPolicy (self-check, used by CI's own validate-environment step)"
    )

    echo ""
    echo "Verifying permissions (policy simulation, no live calls made):"

    local ALL_OK=true
    for ENTRY in "${CHECKS[@]}"; do
        IFS='|' read -r ACTION RESOURCE LABEL <<<"$ENTRY"

        local DECISION
        DECISION=$(aws iam simulate-principal-policy \
            --policy-source-arn "$ROLE_ARN" \
            --action-names "$ACTION" \
            --resource-arns "$RESOURCE" \
            --query "EvaluationResults[0].EvalDecision" \
            --output text)

        if [[ "$DECISION" == "allowed" ]]; then
            echo "  [OK]     $LABEL"
        else
            echo "  [MISSING] $LABEL -> $DECISION"
            ALL_OK=false
        fi
    done

    if [[ "$ALL_OK" != "true" ]]; then
        echo ""
        echo "One or more required actions are not allowed. Fix the policy above before wiring this role into GitHub."
        exit 1
    fi

    echo ""
    echo "All required actions verified allowed."
}

create_or_reuse_oidc_provider
create_deploy_role
create_inline_policy
verify_permissions

echo ""
echo "bflow-mcp GitHub OIDC role ready."
echo "Set these as GitHub repo secrets/variables before enabling the workflow:"
echo "  secrets.AWS_ROLE_ARN = $(require_output GITHUB_ACTIONS_ROLE_ARN)"
