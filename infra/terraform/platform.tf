resource "aws_ecr_repository" "mcp" {
  name                 = var.ecr_repository_name
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  lifecycle {
    ignore_changes = [tags]
  }
}

resource "aws_ecr_lifecycle_policy" "mcp" {
  count      = var.ecr_lifecycle_max_images == null ? 0 : 1
  repository = aws_ecr_repository.mcp.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the most recent deploy images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_lifecycle_max_images
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "mcp" {
  name              = var.cloudwatch_log_group_name
  retention_in_days = var.log_retention_days

  lifecycle {
    ignore_changes = [tags]
  }
}

data "aws_iam_policy_document" "ecs_task_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = var.ecs_execution_role_name
  assume_role_policy = data.aws_iam_policy_document.ecs_task_trust.json

  lifecycle {
    ignore_changes = [tags]
  }
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# bflow-mcp never calls an AWS service directly. ECS only requires a task role
# to exist, so it has no policies by default (ADR-0010 section 1). The only
# opt-in is the OTel trace export below (otel_traces_enabled).
resource "aws_iam_role" "ecs_task" {
  name               = var.ecs_task_role_name
  assume_role_policy = data.aws_iam_policy_document.ecs_task_trust.json

  lifecycle {
    ignore_changes = [tags]
  }
}

# Same single permission as the monolith's bflow-otel-traces policy. Opt-in so
# the default footprint stays at zero permissions.
resource "aws_iam_role_policy" "ecs_task_otel" {
  count = var.otel_traces_enabled ? 1 : 0
  name  = "bflow-mcp-otel-traces"
  role  = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "PutTraceSegments"
      Effect   = "Allow"
      Action   = ["xray:PutTraceSegments"]
      Resource = "*"
    }]
  })
}
