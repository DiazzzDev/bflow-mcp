# Same binary as the monolith's dns-sync (one Go source, in the monolith repo),
# deployed as a second function pointed at bflow-mcp (ECS_SERVICE_NAME) and
# attached as an extra
# target on the monolith's EventBridge rule. The Lambda filters by
# detail.group == "service:<ECS_SERVICE_NAME>", so each function ignores the
# other service's tasks.

# Terraform owns the secret container, never its value. Put the Cloudflare
# credentials (CLOUDFLARE_API_TOKEN, CLOUDFLARE_ZONE_ID,
# CLOUDFLARE_DNS_RECORD_ID, CLOUDFLARE_DNS_RECORD_NAME) in it with the AWS CLI
# or console so they never enter local Terraform state.
resource "aws_secretsmanager_secret" "cloudflare_dns" {
  count                   = var.dns_sync_enabled ? 1 : 0
  name                    = var.dns_sync_secret_name
  recovery_window_in_days = 7

  lifecycle {
    ignore_changes = [description, force_overwrite_replica_secret, recovery_window_in_days, tags]
  }
}

data "aws_iam_policy_document" "dns_sync_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "dns_sync" {
  count              = var.dns_sync_enabled ? 1 : 0
  name               = var.dns_sync_role_name
  assume_role_policy = data.aws_iam_policy_document.dns_sync_trust.json

  lifecycle {
    ignore_changes = [tags]
  }
}

resource "aws_iam_role_policy_attachment" "dns_sync_basic_execution" {
  count      = var.dns_sync_enabled ? 1 : 0
  role       = aws_iam_role.dns_sync[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "dns_sync" {
  count = var.dns_sync_enabled ? 1 : 0
  name  = "${var.dns_sync_function_name}-permissions"
  role  = aws_iam_role.dns_sync[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DescribeEcsAndEni"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks", "ecs:ListTasks", "ec2:DescribeNetworkInterfaces"]
        Resource = "*"
      },
      {
        Sid      = "ReadCloudflareSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = aws_secretsmanager_secret.cloudflare_dns[0].arn
      }
    ]
  })
}

# Created here so retention is managed (Lambda would otherwise auto-create a
# never-expiring group). The monolith's own Lambda group lives in its
# observability.tf; this one is separate, so nothing is shared or duplicated.
resource "aws_cloudwatch_log_group" "dns_sync" {
  count             = var.dns_sync_enabled ? 1 : 0
  name              = "/aws/lambda/${var.dns_sync_function_name}"
  retention_in_days = var.dns_sync_log_retention_days

  lifecycle {
    ignore_changes = [tags]
  }
}

resource "aws_lambda_function" "dns_sync" {
  count            = var.dns_sync_enabled ? 1 : 0
  function_name    = var.dns_sync_function_name
  role             = aws_iam_role.dns_sync[0].arn
  filename         = var.dns_sync_zip_path
  source_code_hash = filebase64sha256(var.dns_sync_zip_path)
  depends_on       = [aws_cloudwatch_log_group.dns_sync]
  handler          = "bootstrap"
  runtime          = "provided.al2023"
  architectures    = ["arm64"]
  timeout          = 60
  memory_size      = 128

  environment {
    variables = {
      ECS_CLUSTER_NAME      = data.aws_ecs_cluster.shared.cluster_name
      ECS_SERVICE_NAME      = var.ecs_service_name
      CLOUDFLARE_SECRET_ARN = aws_secretsmanager_secret.cloudflare_dns[0].arn
    }
  }

  lifecycle {
    ignore_changes = [tags]

    precondition {
      condition     = var.dns_sync_zip_path != null && fileexists(var.dns_sync_zip_path)
      error_message = "dns_sync_zip_path must point to an existing lambda_function.zip (build it from BFlow-Financial-Engine/infra/dns-sync, see README)."
    }
  }
}

resource "aws_lambda_permission" "eventbridge" {
  count         = var.dns_sync_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.dns_sync[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = local.shared_event_rule_arn
}

resource "aws_cloudwatch_event_target" "dns_sync" {
  count     = var.dns_sync_enabled ? 1 : 0
  rule      = var.shared_event_rule_name
  target_id = var.dns_sync_event_target_id
  arn       = aws_lambda_function.dns_sync[0].arn
}
