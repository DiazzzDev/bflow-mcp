# Everything in this file is owned by BFlow-Financial-Engine's Terraform and is
# only looked up here. bflow-mcp deliberately shares the monolith's cluster,
# subnets and security group (pre-revenue: no extra network footprint), so this
# stack must never declare security groups or security group rules of its own.

data "aws_ecs_cluster" "shared" {
  cluster_name = var.shared_ecs_cluster_name
}

data "aws_security_group" "shared_ecs" {
  name = var.shared_ecs_security_group_name
}

data "aws_subnet" "shared_public" {
  for_each = toset(var.shared_public_subnet_names)

  filter {
    name   = "tag:Name"
    values = [each.value]
  }
}

# The account-wide GitHub OIDC provider is created by the monolith stack.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

# The AWS provider has no data source for EventBridge rules, so the monolith's
# rule is addressed by name and its ARN is built (default event bus).
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  shared_event_rule_arn = "arn:${data.aws_partition.current.partition}:events:${var.aws_region}:${data.aws_caller_identity.current.account_id}:rule/${var.shared_event_rule_name}"

  shared_public_subnet_ids = [for name in var.shared_public_subnet_names : data.aws_subnet.shared_public[name].id]
}
