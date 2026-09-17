#!/usr/bin/env bash
# 20-mcp-security-group.sh — Authorizes Cloudflare's IPv4 ranges to reach
# bflow-mcp's container port (8081) on the shared ECS security group.
#
# bflow-mcp reuses the SAME security group as the monolith
# (BFlow-Financial-Engine's ECS_SECURITY_GROUP_ID, copied into this
# repo's config.env as ECS_SECURITY_GROUP). That group's only ingress
# rule was opened for the monolith's own APP_PORT by
# BFlow-Financial-Engine/infra/bootstrap/05-security-groups.sh — nothing
# in bflow-mcp's infra/16-19 ever authorized 8081. Same pattern, just
# scoped to this service's port. Idempotent: re-running only adds CIDRs
# that aren't already authorized.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source "$SCRIPT_DIR/config.env"
source "$SCRIPT_DIR/outputs.env"
source "$SCRIPT_DIR/lib/helpers.sh"

MCP_APP_PORT=8081
CLOUDFLARE_IPV4_URL="${CLOUDFLARE_IPV4_URL:-https://www.cloudflare.com/ips-v4}"

command -v curl >/dev/null || {
    echo "curl is required."
    exit 1
}

authorize_cloudflare_ranges() {
    local SECURITY_GROUP_ID="$1"
    local PORT="$2"
    local URL="$3"

    echo "Downloading Cloudflare ipv4 ranges..."

    local CIDRS
    CIDRS=$(curl -fsSL "$URL")

    [[ -z "$CIDRS" ]] && {
        echo "Unable to download Cloudflare ranges."
        exit 1
    }

    while read -r CIDR; do
        [[ -z "$CIDR" ]] && continue

        EXISTS=$(aws ec2 describe-security-groups \
            --region "$AWS_REGION" \
            --group-ids "$SECURITY_GROUP_ID" \
            --query "SecurityGroups[0].IpPermissions[?FromPort==\`${PORT}\` && IpRanges[?CidrIp=='${CIDR}']]" \
            --output text)

        if [[ -z "$EXISTS" ]]; then
            echo "Authorizing $CIDR on port $PORT"

            aws ec2 authorize-security-group-ingress \
                --region "$AWS_REGION" \
                --group-id "$SECURITY_GROUP_ID" \
                --protocol tcp \
                --port "$PORT" \
                --cidr "$CIDR"
        fi
    done <<<"$CIDRS"
}

authorize_cloudflare_ranges \
    "$ECS_SECURITY_GROUP" \
    "$MCP_APP_PORT" \
    "$CLOUDFLARE_IPV4_URL"

echo ""
echo "Port $MCP_APP_PORT authorized for Cloudflare's IPv4 ranges on $ECS_SECURITY_GROUP."