#!/usr/bin/env bash
# 17-mcp-cognito-client.sh — Creates the Cognito Resource Server (custom
# scopes) and a dedicated public App Client for bflow-mcp, separate from
# the web frontend's App Client.
#
# Design constraint this implements: bflow-mcp is only ever used by
# existing BFlow users. There is no sign-up flow here — this is login +
# consent against the SAME user pool the web app already uses, just a
# different App Client so scopes/redirect URIs are managed independently.
#
# Requires: infra/mcp-cognito.env (COGNITO_USER_POOL_ID — the part after
# the last "/" in your COGNITO_ISSUER_URI, e.g. us-east-1_M6tN3H360).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ ! -f "$SCRIPT_DIR/mcp-cognito.env" ]]; then
    echo "infra/mcp-cognito.env not found."
    echo "Create it with:"
    echo "  COGNITO_USER_POOL_ID=us-east-1_XXXXXXXXX"
    echo "  AWS_REGION=us-east-1"
    exit 1
fi

source "$SCRIPT_DIR/mcp-cognito.env"

RESOURCE_SERVER_ID="bflow-mcp"
RESOURCE_SERVER_NAME="BFlow MCP"
APP_CLIENT_NAME="bflow-mcp-agent-client"

# Scope catalog per ADR-0009 §2 (naming corrected to Cognito's actual
# <resource-server-identifier>/<ScopeName> token format — colons aren't
# part of Cognito's convention, dots are).
SCOPES_JSON='[
  {"ScopeName":"wallets.read","ScopeDescription":"View wallets"},
  {"ScopeName":"transactions.read","ScopeDescription":"View transactions"},
  {"ScopeName":"transactions.write","ScopeDescription":"Create or update transactions"},
  {"ScopeName":"budgets.read","ScopeDescription":"View budgets"},
  {"ScopeName":"budgets.write","ScopeDescription":"Create or update budgets"},
  {"ScopeName":"recurring.read","ScopeDescription":"View recurring items"},
  {"ScopeName":"recurring.write","ScopeDescription":"Create or update recurring items"},
  {"ScopeName":"dashboard.read","ScopeDescription":"View dashboard/spending summaries"}
]'

create_or_update_resource_server() {
    if aws cognito-idp describe-resource-server \
        --region "$AWS_REGION" \
        --user-pool-id "$COGNITO_USER_POOL_ID" \
        --identifier "$RESOURCE_SERVER_ID" >/dev/null 2>&1; then

        aws cognito-idp update-resource-server \
            --region "$AWS_REGION" \
            --user-pool-id "$COGNITO_USER_POOL_ID" \
            --identifier "$RESOURCE_SERVER_ID" \
            --name "$RESOURCE_SERVER_NAME" \
            --scopes "$SCOPES_JSON" >/dev/null
        echo "Resource server '$RESOURCE_SERVER_ID' updated."
    else
        aws cognito-idp create-resource-server \
            --region "$AWS_REGION" \
            --user-pool-id "$COGNITO_USER_POOL_ID" \
            --identifier "$RESOURCE_SERVER_ID" \
            --name "$RESOURCE_SERVER_NAME" \
            --scopes "$SCOPES_JSON" >/dev/null
        echo "Resource server '$RESOURCE_SERVER_ID' created."
    fi
}

create_or_update_app_client() {
    local EXISTING_CLIENT_ID
    local ALLOWED_SCOPES=(
        "openid"
        "$RESOURCE_SERVER_ID/wallets.read"
        "$RESOURCE_SERVER_ID/transactions.read"
        "$RESOURCE_SERVER_ID/transactions.write"
        "$RESOURCE_SERVER_ID/budgets.read"
        "$RESOURCE_SERVER_ID/budgets.write"
        "$RESOURCE_SERVER_ID/recurring.read"
        "$RESOURCE_SERVER_ID/recurring.write"
        "$RESOURCE_SERVER_ID/dashboard.read"
    )

    # NOTE: CallbackURLs below is a STARTING list for local testing only
    # (the MCP Inspector's default redirect). Cognito has no Dynamic
    # Client Registration, so every AI platform you connect this to
    # later (Claude, ChatGPT, etc.) needs its OWN callback URL added
    # here manually, per that platform's connector-setup documentation,
    # before it can complete the OAuth flow. There is no way to automate
    # that in advance — add them as you onboard each platform.
    EXISTING_CLIENT_ID=$(aws cognito-idp list-user-pool-clients \
        --region "$AWS_REGION" \
        --user-pool-id "$COGNITO_USER_POOL_ID" \
        --query "UserPoolClients[?ClientName=='$APP_CLIENT_NAME'].ClientId" \
        --output text)

    if [[ -n "$EXISTING_CLIENT_ID" && "$EXISTING_CLIENT_ID" != "None" ]]; then
        aws cognito-idp update-user-pool-client \
            --region "$AWS_REGION" \
            --user-pool-id "$COGNITO_USER_POOL_ID" \
            --client-id "$EXISTING_CLIENT_ID" \
            --allowed-o-auth-flows code \
            --allowed-o-auth-flows-user-pool-client \
            --allowed-o-auth-scopes "${ALLOWED_SCOPES[@]}" \
            --supported-identity-providers COGNITO Google \
            --callback-urls "http://127.0.0.1:6274/oauth/callback" "http://localhost:6274/oauth/callback" \
            --prevent-user-existence-errors ENABLED \
            >/dev/null

        echo "App client '$APP_CLIENT_NAME' updated. ClientId=$EXISTING_CLIENT_ID" >&2
    else
        EXISTING_CLIENT_ID=$(aws cognito-idp create-user-pool-client \
            --region "$AWS_REGION" \
            --user-pool-id "$COGNITO_USER_POOL_ID" \
            --client-name "$APP_CLIENT_NAME" \
            --no-generate-secret \
            --allowed-o-auth-flows code \
            --allowed-o-auth-flows-user-pool-client \
            --allowed-o-auth-scopes "${ALLOWED_SCOPES[@]}" \
            --supported-identity-providers COGNITO Google \
            --callback-urls "http://127.0.0.1:6274/oauth/callback" "http://localhost:6274/oauth/callback" \
            --prevent-user-existence-errors ENABLED \
            --query "UserPoolClient.ClientId" --output text)

        echo "App client '$APP_CLIENT_NAME' created. ClientId=$EXISTING_CLIENT_ID" >&2
    fi

    echo "$EXISTING_CLIENT_ID"
}

# On a Managed Login (v2) domain, an app client with NO branding style
# assigned gets a hard "Login pages unavailable" on its login page — it
# does not fall back to a default. This assigns Cognito's own default
# look, which is enough to unblock the OAuth flow (no design work
# needed). Idempotent: skips if this client already has one.
create_or_update_managed_login_branding() {
    local CLIENT_ID="$1"

    if aws cognito-idp describe-managed-login-branding-by-client \
        --region "$AWS_REGION" \
        --user-pool-id "$COGNITO_USER_POOL_ID" \
        --client-id "$CLIENT_ID" >/dev/null 2>&1; then
        echo "Managed login branding already assigned to $CLIENT_ID."
        return
    fi

    aws cognito-idp create-managed-login-branding \
        --region "$AWS_REGION" \
        --user-pool-id "$COGNITO_USER_POOL_ID" \
        --client-id "$CLIENT_ID" \
        --use-cognito-provided-values \
        >/dev/null

    echo "Managed login branding created for $CLIENT_ID (Cognito default look)."
}

create_or_update_resource_server
APP_CLIENT_ID=$(create_or_update_app_client)
create_or_update_managed_login_branding "$APP_CLIENT_ID"

echo ""
echo "Domain confirmed already: us-east-1m6tn3h360"
echo "Hosted UI authorize endpoint:"
echo "  https://us-east-1m6tn3h360.auth.us-east-1.amazoncognito.com/oauth2/authorize"