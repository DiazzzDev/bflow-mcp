variable "aws_region" {
  type        = string
  description = "AWS region. Must match the monolith's region: the cluster and subnets are shared."
  default     = "us-east-1"
}

# ---------------------------------------------------------------------------
# Resources owned by BFlow-Financial-Engine's Terraform. They are only READ
# here (data sources); this stack never creates or modifies them.
# ---------------------------------------------------------------------------

variable "shared_ecs_cluster_name" {
  type        = string
  description = "Cluster shared with the monolith (no second cluster for bflow-mcp)."
  default     = "bflow-cluster"
}

variable "shared_ecs_security_group_name" {
  type        = string
  description = "Security group shared with the monolith. Its ingress rules (including the Cloudflare ranges on the MCP port) are owned by the monolith's ecs_ingress_rules."
  default     = "bflow-ecs-sg"
}

variable "shared_public_subnet_names" {
  type        = list(string)
  description = "Name tags of the monolith's public subnets, in the order exposed as ECS_PUBLIC_SUBNET_1/2."
  default     = ["bflow-public-a", "bflow-public-b"]
}

variable "shared_event_rule_name" {
  type        = string
  description = "EventBridge rule owned by the monolith (only exists when its dns_sync_enabled is true). bflow-mcp's Lambda is attached as an additional target."
  default     = "bflow-ecs-task-state-change"
}

# ---------------------------------------------------------------------------
# bflow-mcp service
# ---------------------------------------------------------------------------

variable "mcp_container_port" {
  type        = number
  description = "Container port. Informational: the ingress rule lives in the monolith's security group."
  default     = 8081
}

variable "ecs_service_name" {
  type    = string
  default = "bflow-mcp"
}

variable "ecs_task_family" {
  type    = string
  default = "bflow-mcp"
}

variable "ecs_container_name" {
  type    = string
  default = "bflow-mcp"
}

variable "ecr_repository_name" {
  type    = string
  default = "bflow-mcp"
}

variable "ecr_lifecycle_max_images" {
  type        = number
  description = "Opt-in after migration: retain only this many images. Null preserves the current repository policy state."
  default     = null
  nullable    = true
}

variable "ecs_execution_role_name" {
  type    = string
  default = "bflow-mcp-ecs-execution-role"
}

variable "ecs_task_role_name" {
  type        = string
  description = "Intentionally has zero permissions attached (ADR-0010 section 1)."
  default     = "bflow-mcp-ecs-task-role"
}

variable "cloudwatch_log_group_name" {
  type    = string
  default = "/ecs/bflow-mcp"
}

variable "log_retention_days" {
  type        = number
  description = "Matches the retention the bash bootstrap applied."
  default     = 14
}

# ---------------------------------------------------------------------------
# GitHub Actions OIDC deploy role
# ---------------------------------------------------------------------------

variable "github_owner" {
  type    = string
  default = "DiazzzDev"
}

variable "github_repository" {
  type    = string
  default = "bflow-mcp"
}

variable "github_environment" {
  type    = string
  default = "production"
}

variable "github_owner_id" {
  type        = string
  description = "Immutable GitHub owner id. The existing trust policy uses the id-based subject claim; set both ids to null to fall back to the name-based claim."
  default     = "174159480"
  nullable    = true
}

variable "github_repository_id" {
  type        = string
  description = "Immutable GitHub repository id (see github_owner_id)."
  default     = "1368671239"
  nullable    = true
}

variable "github_actions_role_name" {
  type    = string
  default = "bflow-mcp-github-actions-role"
}

# ---------------------------------------------------------------------------
# Cognito (opt-in: the user pool predates this Terraform migration)
# ---------------------------------------------------------------------------

variable "manage_cognito" {
  type        = bool
  description = "Opt-in. Manages the resource server, the app client and its Managed Login branding in an existing user pool."
  default     = false
}

variable "cognito_user_pool_id" {
  type        = string
  description = "Existing Cognito user pool shared with the web app."
  default     = null
  nullable    = true
}

variable "cognito_hosted_ui_domain" {
  type        = string
  description = "Hosted UI domain, passed through to the deployment variables output only."
  default     = null
  nullable    = true
}

variable "cognito_resource_server_identifier" {
  type    = string
  default = "bflow-mcp"
}

variable "cognito_app_client_name" {
  type    = string
  default = "bflow-mcp-agent-client"
}

variable "mcp_public_base_url" {
  type        = string
  description = "Public base URL of bflow-mcp (BFLOW_MCP_PUBLIC_BASE_URL). Its /oauth/callback is the callback Cognito sees for every DCR client."
  default     = "https://mcp.bflow-studio.com"
}

variable "cognito_extra_callback_urls" {
  type        = list(string)
  description = "Pre-DCR direct callbacks. update-user-pool-client replaces the whole list, so every platform still using the real client id must stay listed."
  default = [
    "http://127.0.0.1:6274/oauth/callback",
    "http://localhost:6274/oauth/callback",
    "https://chatgpt.com/connector_platform_oauth_redirect",
    "https://claude.ai/api/mcp/auth_callback",
  ]
}

variable "cognito_scopes" {
  type        = map(string)
  description = "Custom scopes of the resource server (scope name => description)."
  default = {
    "wallets.read"       = "View wallets"
    "transactions.read"  = "View transactions"
    "transactions.write" = "Create or update transactions"
    "budgets.read"       = "View budgets"
    "budgets.write"      = "Create or update budgets"
    "recurring.read"     = "View recurring items"
    "recurring.write"    = "Create or update recurring items"
    "dashboard.read"     = "View dashboard/spending summaries"
    "categories.read"    = "View transaction categories"
  }
}

# ---------------------------------------------------------------------------
# DNS sync (opt-in, same convention as the monolith)
# ---------------------------------------------------------------------------

variable "dns_sync_enabled" {
  type        = bool
  description = "Creates the DNS-sync Lambda for mcp.bflow-studio.com after its zip exists and the shared EventBridge rule is deployed."
  default     = false
}

variable "dns_sync_zip_path" {
  type        = string
  description = "Built Lambda zip. The Go source lives ONLY in BFlow-Financial-Engine/infra/dns-sync (same binary, configured per service by env vars); build it there and point this at the resulting lambda_function.zip."
  default     = null
  nullable    = true
}

variable "dns_sync_log_retention_days" {
  type        = number
  description = "Retention of the Lambda's log group. Same default as the monolith's (7 days)."
  default     = 7
}

variable "dns_sync_function_name" {
  type        = string
  description = "Verify against `aws lambda list-functions`: the bash script derived it as <PROJECT_NAME>-mcp-dns-sync."
  default     = "bflow-mcp-mcp-dns-sync"
}

variable "dns_sync_role_name" {
  type    = string
  default = "bflow-mcp-mcp-dns-sync-lambda-role"
}

variable "dns_sync_secret_name" {
  type        = string
  description = "Secret container for the Cloudflare credentials. Terraform never holds its value."
  default     = "bflow-mcp/mcp-cloudflare-dns"
}

variable "dns_sync_event_target_id" {
  type        = string
  description = "Target id on the shared rule. The bash script used Id=2 (the monolith's Lambda is the other target)."
  default     = "2"
}

# ---------------------------------------------------------------------------
# Observability (OTel). Account-wide pieces (Transaction Search, SNS topic,
# dashboards, alarms) belong to the monolith's observability.tf; this stack
# only grants what the MCP task needs to emit traces.
# ---------------------------------------------------------------------------

variable "otel_traces_enabled" {
  type        = bool
  description = "Grants the task role xray:PutTraceSegments so the ADOT Java agent can export traces straight to X-Ray (same mechanism as the monolith). The agent itself is switched on from deploy.yml, not here."
  default     = false
}

variable "cognito_app_client_id" {
  description = "Cognito app client ID used by the MCP server"
  type        = string
  default     = null
}