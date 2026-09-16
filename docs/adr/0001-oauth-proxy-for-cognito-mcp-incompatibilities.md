# ADR-0001: OAuth Proxy for Cognito/MCP Incompatibilities

- **Status:** Accepted (implemented and working end-to-end against a
  real Cognito user pool as of this writing)
- **Date:** 2026-09-13
- **Related:** BFlow-Financial-Engine's ADR-0009 (Scope Model),
  ADR-0010 (Security Boundaries) — this ADR covers what those left as
  an open question: how token issuance actually works.

## Context

The MCP Authorization spec expects a standard OAuth 2.1 Authorization
Server: dynamic client registration (RFC 7591), resource indicators
(RFC 8707), and strict metadata discovery (RFC 8414). Amazon Cognito —
the only Authorization Server BFlow has, and the one this service is
required to use (existing users only, no separate identity system) —
implements none of the first two, and is stricter about the third than
expected. Every one of the four gaps below was discovered by actually
running an MCP client (the official MCP Inspector) against a real
Cognito user pool, not anticipated in advance.

## The four incompatibilities, and the fix for each

### 1. No Dynamic Client Registration

**Symptom:** an MCP client, after discovering the Authorization Server,
tries `POST /register` to create itself a client — Cognito has no such
endpoint, request fails.

**Fix:** don't rely on DCR at all. Provision one static, pre-registered
Cognito App Client for bflow-mcp (`infra/17-mcp-cognito-client.sh`),
public (no secret, PKCE required), and hand its `client_id` to whatever
MCP client needs to connect — entered manually in that client's own
"pre-configured OAuth credentials" setting (the MCP Inspector has one;
Claude/ChatGPT connector setup screens have an equivalent). This is a
real, permanent limitation, not a bug to eventually fix: every new AI
platform connected to bflow-mcp needs this manual step, and its own
callback URL added to the Cognito App Client's allow-list
(`--callback-urls`).

### 2. No RFC 8707 (Resource Indicators)

**Symptom:** a spec-compliant MCP client attaches a `resource` query
parameter to the `/authorize` request (identifying which MCP server
the token is for). Cognito doesn't recognize this parameter and
rejects the request outright: `invalid_request... custom scopes
requested for resource-binding must be assigned to the resource being
requested`.

**Fix:** `OAuthProxyController` sits in front of Cognito's real
`/oauth2/authorize` and `/oauth2/token` endpoints, forwarding every
parameter through untouched **except** `resource`, which it strips.
Nothing else — `code_challenge`, `code_challenge_method`, `state`,
`redirect_uri` — is touched, or PKCE breaks. This is a permanent proxy,
not a workaround to remove later: Cognito has no roadmap commitment (as
far as this ADR's author could find) to implement RFC 8707.

### 3. Login pages unavailable without Managed Login branding

**Symptom:** a brand-new Cognito App Client, on a Managed Login (v2)
domain, returns "Login pages unavailable. Please contact an
administrator." the moment a user is sent to log in — it does not fall
back to any default appearance.

**Fix:** `infra/17-mcp-cognito-client.sh` calls
`aws cognito-idp create-managed-login-branding
--use-cognito-provided-values` right after creating the App Client.
No custom design work needed — Cognito's own default look is enough to
unblock the flow. Skipping this step is the single most confusing
failure mode in this whole list, because the error message gives no
hint that branding is the cause.

### 4. Strict RFC 8414 §3.3 issuer matching (client-side, not Cognito's fault)

**Symptom:** with the proxy from #2 in place, and bflow-mcp's own
`/.well-known/oauth-authorization-server` document naming Cognito's
real issuer in its `issuer` field, spec-compliant clients (the
Inspector included) reject the metadata outright: *"Issuer mismatch...
expected `http://localhost:8081`, received
`https://cognito-idp...`."* RFC 8414 §3.3 requires the metadata's
`issuer` field to exactly equal the URL used to discover it.

**Fix:** `AuthorizationServerMetadataController` publishes bflow-mcp's
**own** URL as `issuer` — not Cognito's — satisfying the client-side
check. This is safe specifically because `SecurityConfig`'s JWT
validation reads `spring.security.oauth2.resourceserver.jwt.issuer-uri`
directly from configuration, never from this metadata document — the
two are unrelated as far as Spring Security's resource-server
validation is concerned. `jwks_uri` still points at Cognito's real
JWKS endpoint, since Cognito is still who actually signs the tokens.

## Consequences

### Positive

- Every one of these is a one-time, permanent fix — none is a
  workaround expected to be removed once Cognito adds a missing
  feature. Future maintenance work is additive (new AI platforms need
  a manual client-id step, per #1), not a re-fight of the same battles.
- The proxy (`OAuthProxyController`) never mints, signs, or inspects a
  token — it's a strip-and-forward relay. Cognito remains the sole
  source of truth for authentication.

### Negative

- Onboarding a new AI platform (Claude, ChatGPT, etc.) as a connector
  to bflow-mcp is a manual, per-platform step (#1) — there is no way to
  automate registering a new callback URL or handing over the
  `client_id`, because Cognito has no DCR to automate against.
- If Cognito ever adds RFC 8707 support, #2's proxy becomes optional
  rather than required — worth revisiting then, not a reason to remove
  it now.