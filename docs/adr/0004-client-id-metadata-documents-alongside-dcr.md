# ADR-0004: Client ID Metadata Documents alongside DCR

- **Status:** Accepted. Implemented; stays off until
  `BFLOW_MCP_PROXY_CIMD_ALLOWED_CLIENT_IDS` is set.
- **Date:** 2026-10-24
- **Related:** ADR-0001 (OAuth proxy), ADR-0002 (stateless DCR shim and
  redirect allowlist — extended, not replaced).

## Context

The MCP authorization spec (2025-11-25) makes Client ID Metadata
Documents (CIMD) the preferred way for a client to identify itself to an
authorization server, and demotes Dynamic Client Registration (DCR) to
an optional mechanism kept for backward compatibility. A client's
`client_id` is then an HTTPS URL, and the server downloads a JSON
document from it that declares the client's `redirect_uris`.

Clients that prefer CIMD use it when the authorization server advertises
`client_id_metadata_document_supported`. Some clients, and some
workspaces of a client, still only use DCR.

## Decision

Add CIMD next to DCR. **Do not remove DCR.**

1. **Advertise CIMD** in `/.well-known/oauth-authorization-server`
   (`client_id_metadata_document_supported: true`), only while at least
   one client is allowlisted. `registration_endpoint` stays.
2. **A strict allowlist of `client_id` URLs**
   (`bflow.mcp.proxy.cimd.allowed-client-ids`, comma-separated, exact
   match, empty by default). It follows the pattern of
   `allowed-redirect-uris`: onboarding a platform is one config entry.
   Entries must be HTTPS URLs with a path, no fragment, no credentials
   and no dot segments, or the service refuses to start.
3. **`/oauth/authorize` branches on the shape of `client_id`.** An
   `https://` value takes the CIMD path; anything else takes the
   existing DCR path, unchanged.
4. **CIMD validation** (`CimdMetadataClient`): the `client_id` must be
   allowlisted before any network call; the document's own `client_id`
   must equal the URL exactly; `redirect_uris` must be a non-empty array
   of strings; `token_endpoint_auth_method`, if present, must be `none`.
   The requested `redirect_uri` must be declared by the document **and**
   be on the proxy's own `allowed-redirect-uris` list.
5. **`/oauth/token`** treats an allowlisted CIMD `client_id` like a DCR
   one: it substitutes the real Cognito App Client id and the fixed
   Cognito-facing callback. Cognito still sees one static client and the
   proxy still mints no tokens.

### The fetch

`JdkCimdDocumentFetcher` never follows redirects, times out in seconds,
accepts only a `200` with a JSON content type, and caps the body at
64 KB. Results are cached in memory for a fixed five minutes per
allowlisted URL (failures are not cached). The cache holds public
documents, not transactions or sessions, so ADR-0002's statelessness
holds: losing it or running several instances only costs a fetch.

## Why an allowlist and not any HTTPS URL

Accepting any URL would make the proxy download addresses chosen by
arbitrary callers (SSRF, including the ECS task metadata endpoint) and
trust identities nobody reviewed. With an allowlist, only approved URLs
are ever fetched, and the fetcher does not need private-address
blocking. **If the allowlist is ever relaxed, SSRF protection (blocking
private, link-local and metadata addresses, DNS-rebinding defence) must
be added to the fetcher first.**

The cost is that a new platform needs a configuration change, which is
already true for redirect URIs.

## Consequences

- **A client that prefers CIMD does not fall back to DCR.** Once CIMD is
  advertised, a client that supports it identifies itself with its URL
  instead of registering. If that URL is not allowlisted, authorization
  fails with a 400, and such clients are reported not to retry with DCR
  (Claude Code is the documented case). So the allowlist must contain
  every CIMD-capable client currently connecting, or enabling CIMD can
  break a client that works today.
- Clients that support CIMD are identified by a domain they control,
  instead of self-reported DCR registrations.
- All instances of a client share one `client_id`, so there is no
  per-instance identity. Today every DCR client already maps to the one
  Cognito App Client, so little is lost; the proxy's logs are the only
  per-client trail.
- Two registration paths to maintain until DCR can be retired.
- A rejected CIMD `client_id` is logged (sanitised and truncated), which
  is how the exact value a real client sends can be learned.
- The cache TTL is fixed; the spec asks for HTTP cache headers to be
  honoured. Revisit if a client's document changes often.

## Open items

- **Per-client user consent.** The spec requires a proxy that fronts a
  third-party authorization server with a static client id to obtain the
  user's consent for each dynamically identified client. This proxy
  redirects straight to Cognito and adds no consent step; the exact
  redirect allowlists are the mitigation. This applies equally to DCR
  and is not solved here.
- **Loopback redirects** (e.g. the MCP Inspector's `127.0.0.1`): another
  local process could claim them. The exact redirect allowlist is the
  defence and is kept in addition to the document.
- **Which clients use CIMD, and with which `client_id`.** Documents
  checked on 2026-10-24:
  - Claude (`https://claude.ai/oauth/mcp-oauth-client-metadata`): `200`,
    JSON, `client_id` equal to the URL, redirect
    `https://claude.ai/api/mcp/auth_callback`,
    `token_endpoint_auth_method: none`. Accepted by this proxy.
  - ChatGPT (`https://chatgpt.com/oauth/client.json`): valid document,
    but it declares `token_endpoint_auth_method: private_key_jwt`. This
    proxy supports public clients with PKCE only and rejects that on
    purpose, so ChatGPT's URL must **not** be allowlisted. It is not
    known whether ChatGPT falls back to DCR once CIMD is advertised, so
    enabling CIMD may break it. Test ChatGPT right after enabling, and
    roll back if it cannot connect.
  - Claude Code and VS Code are listed in public documentation
    (`https://claude.ai/oauth/claude-code-client-metadata`,
    `https://vscode.dev/oauth/client-metadata.json`) and not verified
    here. The MCP Inspector's support is unknown.
- **Claude Code** publishes loopback redirect URIs without a port and
  picks a random port at run time. The exact-match redirect allowlist
  does not support that, with DCR or with CIMD, so it is out of scope
  here.
- The underlying IETF document is still a draft and may change.

## Rollout and rollback

1. Check each candidate document first: it must answer `200` with a
   JSON content type, its `client_id` must equal its URL, and its
   `redirect_uris` must include the redirect the platform uses.
2. Set the repository variable `BFLOW_MCP_PROXY_CIMD_ALLOWED_CLIENT_IDS`
   to every CIMD-capable client in use that this proxy can serve (see
   the first consequence below and the ChatGPT note under Open items),
   and redeploy. Unset or empty keeps CIMD off.
3. Connect each real client and watch the logs. If one is rejected, the
   log line shows the `client_id` it sent.
4. Rollback: unset the variable and redeploy. DCR is unaffected at every
   step.

DCR can be retired only once the clients in use are confirmed to connect
through CIMD; that is a separate decision.
