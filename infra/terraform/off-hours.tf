# EventBridge Scheduler calls ECS UpdateService directly. The task definition
# remains registered; only the service desired count changes, so the next
# morning uses the same reviewed image and configuration.

locals {
  mcp_ecs_service_arn = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/${data.aws_ecs_cluster.shared.cluster_name}/${var.ecs_service_name}"
}

data "aws_iam_policy_document" "mcp_off_hours_scheduler_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "mcp_off_hours_scheduler" {
  count              = var.mcp_off_hours_enabled ? 1 : 0
  name               = "${var.ecs_service_name}-off-hours-scheduler-role"
  assume_role_policy = data.aws_iam_policy_document.mcp_off_hours_scheduler_trust.json

  lifecycle {
    ignore_changes = [tags]
  }
}

resource "aws_iam_role_policy" "mcp_off_hours_scheduler" {
  count = var.mcp_off_hours_enabled ? 1 : 0
  name  = "${var.ecs_service_name}-off-hours-update-service"
  role  = aws_iam_role.mcp_off_hours_scheduler[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "SetMcpServiceDesiredCount"
      Effect   = "Allow"
      Action   = "ecs:UpdateService"
      Resource = local.mcp_ecs_service_arn
    }]
  })
}

resource "aws_scheduler_schedule" "mcp_off_hours_stop" {
  count                        = var.mcp_off_hours_enabled ? 1 : 0
  name                         = "${var.ecs_service_name}-off-hours-stop"
  description                  = "Set ${var.ecs_service_name} desired count to zero every day at midnight."
  schedule_expression          = "cron(0 0 * * ? *)"
  schedule_expression_timezone = "America/El_Salvador"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:${data.aws_partition.current.partition}:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.mcp_off_hours_scheduler[0].arn
    input = jsonencode({
      cluster      = data.aws_ecs_cluster.shared.cluster_name
      service      = var.ecs_service_name
      desiredCount = 0
    })
  }
}

resource "aws_scheduler_schedule" "mcp_off_hours_start" {
  count                        = var.mcp_off_hours_enabled ? 1 : 0
  name                         = "${var.ecs_service_name}-off-hours-start"
  description                  = "Set ${var.ecs_service_name} desired count to one every day at 08:00."
  schedule_expression          = "cron(0 8 * * ? *)"
  schedule_expression_timezone = "America/El_Salvador"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:${data.aws_partition.current.partition}:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.mcp_off_hours_scheduler[0].arn
    input = jsonencode({
      cluster      = data.aws_ecs_cluster.shared.cluster_name
      service      = var.ecs_service_name
      desiredCount = 1
    })
  }
}
