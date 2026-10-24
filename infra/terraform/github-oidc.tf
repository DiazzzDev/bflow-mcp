locals {
  github_subject = (
    var.github_owner_id != null && var.github_repository_id != null
    ? "repo:${var.github_owner}@${var.github_owner_id}/${var.github_repository}@${var.github_repository_id}:environment:${var.github_environment}"
    : "repo:${var.github_owner}/${var.github_repository}:environment:${var.github_environment}"
  )
}

# Dedicated to bflow-mcp and separate from the monolith's deploy role: this
# repo's CI credentials cannot touch the monolith's resources (ADR-0010).
data "aws_iam_policy_document" "github_actions_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.github_subject]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = var.github_actions_role_name
  assume_role_policy = data.aws_iam_policy_document.github_actions_trust.json

  lifecycle {
    ignore_changes = [tags]
  }
}

# Deliberately minimal: every statement maps to something ci.yml or deploy.yml
# calls. No Secrets Manager, S3, SQS, SNS or Textract (none exist here).
resource "aws_iam_role_policy" "github_actions_deploy" {
  name = "bflow-mcp-github-deploy-policy"
  role = aws_iam_role.github_actions.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ECRAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "ECRPushAndValidate"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload", "ecr:DescribeRepositories",
          "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload",
          "ecr:PutImage", "ecr:UploadLayerPart"
        ]
        Resource = aws_ecr_repository.mcp.arn
      },
      {
        Sid    = "ECSDeploy"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition",
          "ecs:DescribeClusters", "ecs:DescribeServices",
          "ecs:CreateService", "ecs:UpdateService",
          "ecs:ListTasks", "ecs:DescribeTasks", "ecs:TagResource"
        ]
        Resource = "*"
      },
      {
        Sid      = "EC2NetworkingValidate"
        Effect   = "Allow"
        Action   = ["ec2:DescribeSubnets", "ec2:DescribeSecurityGroups", "ec2:DescribeNetworkInterfaces"]
        Resource = "*"
      },
      {
        Sid      = "CloudWatchValidate"
        Effect   = "Allow"
        Action   = ["logs:DescribeLogGroups"]
        Resource = "*"
      },
      {
        Sid      = "IAMValidateAndPass"
        Effect   = "Allow"
        Action   = ["iam:GetRole", "iam:PassRole"]
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn]
      },
      {
        Sid      = "IAMSelfPermissionSimulation"
        Effect   = "Allow"
        Action   = ["iam:SimulatePrincipalPolicy", "iam:GetContextKeysForPrincipalPolicy"]
        Resource = aws_iam_role.github_actions.arn
      }
    ]
  })
}
