output "github_actions_role_arn" {
  value       = aws_iam_role.github_actions.arn
  description = "Set as the AWS_ROLE_ARN secret of the bflow-mcp production environment."
}

output "deployment_variables" {
  description = "Non-sensitive GitHub production variables required by deploy.yml."
  value = {
    AWS_REGION                 = var.aws_region
    ECR_REPOSITORY             = aws_ecr_repository.mcp.repository_url
    ECS_CLUSTER_NAME           = data.aws_ecs_cluster.shared.cluster_name
    ECS_SERVICE_NAME           = var.ecs_service_name
    ECS_CONTAINER_NAME         = var.ecs_container_name
    ECS_TASK_FAMILY            = var.ecs_task_family
    ECS_MCP_EXECUTION_ROLE_ARN = aws_iam_role.ecs_execution.arn
    ECS_MCP_TASK_ROLE_ARN      = aws_iam_role.ecs_task.arn
    CLOUDWATCH_MCP_LOG_GROUP   = aws_cloudwatch_log_group.mcp.name
    ECS_PUBLIC_SUBNET_1        = local.shared_public_subnet_ids[0]
    ECS_PUBLIC_SUBNET_2        = local.shared_public_subnet_ids[1]
    ECS_SECURITY_GROUP         = data.aws_security_group.shared_ecs.id
    COGNITO_ISSUER_URI         = var.cognito_user_pool_id == null ? null : "https://cognito-idp.${var.aws_region}.amazonaws.com/${var.cognito_user_pool_id}"
    COGNITO_HOSTED_UI_DOMAIN   = var.cognito_hosted_ui_domain
    COGNITO_APP_CLIENT_ID = var.cognito_app_client_id
  }
}
