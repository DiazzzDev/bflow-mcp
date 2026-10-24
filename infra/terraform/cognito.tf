# Opt-in (manage_cognito) because the user pool predates this migration.
# bflow-mcp only serves existing BFlow users: login + consent against the same
# pool as the web app, through a dedicated app client so scopes and redirect
# URIs are managed independently.

resource "aws_cognito_resource_server" "mcp" {
  count        = var.manage_cognito ? 1 : 0
  user_pool_id = var.cognito_user_pool_id
  identifier   = var.cognito_resource_server_identifier
  name         = "BFlow MCP"

  dynamic "scope" {
    for_each = var.cognito_scopes
    content {
      scope_name        = scope.key
      scope_description = scope.value
    }
  }

  lifecycle {
    precondition {
      condition     = var.cognito_user_pool_id != null
      error_message = "cognito_user_pool_id is required when manage_cognito is true."
    }
  }
}

resource "aws_cognito_user_pool_client" "mcp" {
  count        = var.manage_cognito ? 1 : 0
  user_pool_id = var.cognito_user_pool_id
  name         = var.cognito_app_client_name

  generate_secret                      = false
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_flows_user_pool_client = true
  supported_identity_providers         = ["COGNITO", "Google"]
  prevent_user_existence_errors        = "ENABLED"

  allowed_oauth_scopes = concat(
    ["openid"],
    [for scope in keys(var.cognito_scopes) : "${aws_cognito_resource_server.mcp[0].identifier}/${scope}"]
  )

  # Since the DCR shim (ADR-0002) Cognito only sees bflow-mcp's own fixed
  # callback for every platform that connects through /oauth/register. New
  # platforms are onboarded in application.yml, not here.
  callback_urls = distinct(concat(
    ["${var.mcp_public_base_url}/oauth/callback"],
    var.cognito_extra_callback_urls
  ))
}

# On a Managed Login (v2) domain an app client with no branding style gets a
# hard "Login pages unavailable". Cognito's default look is enough to unblock
# the OAuth flow.
resource "aws_cognito_managed_login_branding" "mcp" {
  count        = var.manage_cognito ? 1 : 0
  user_pool_id = var.cognito_user_pool_id
  client_id    = aws_cognito_user_pool_client.mcp[0].id

  use_cognito_provided_values = true
}
