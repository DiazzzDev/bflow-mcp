# ADR-0002: Stateless DCR Shim and Redirect URI Allowlist

- **Status:** Accepted (implemented)
- **Date:** 2026-09-17
- **Related:** ADR-0001 (§1 addendum), BFlow-Financial-Engine's
  ADR-0009 (Scope Model) and ADR-0010 (Security Boundaries) — this ADR
  keeps that decision's "no new database, new or shared" constraint
  intact.

## Context

ADR-0001 §1 accepted, as a permanent limitation, that onboarding a new
AI platform to bflow-mcp meant hand-running
`infra/17-mcp-cognito-client.sh` against Cognito: adding the
platform's fixed callback URL to `--callback-urls` and, the first
time, copying its `client_id` into that platform's own connector
setup. That's a real AWS CLI script touching Cognito directly, run by
hand, for every platform — more friction than the problem needs, given
that every platform bflow-mcp has ever connected to uses one fixed,
publicly documented callback URL with no dynamic component.

## Decision

Add a **stateless** RFC 7591 Dynamic Client Registration shim in
front of Cognito's still-nonexistent DCR support:

- `POST /oauth/register` issues a `client_id` that is itself a signed
  JWT (`ProxyTokenCodec`) carrying the caller's `redirect_uri` —
  nothing is persisted, so this adds no database, matching
  ADR-0009/0010's constraint.
- `/oauth/authorize` decodes that JWT, confirms the requested
  `redirect_uri` matches it, and forwards to Cognito using the one
  real, static `bflow-mcp-agent-client` App Client id — Cognito still
  never sees a per-platform client. The caller's real `redirect_uri`
  and `state` travel through Cognito's login/consent UI packed into a
  short-lived "outer state" JWT, since Cognito's own `redirect_uri` for
  this flow is now always bflow-mcp's fixed `/oauth/callback`.
- `/oauth/callback` (new) unpacks that outer-state JWT and does the
  final redirect back to the caller's real destination.

### The open-redirect gap, and closing it

The spec this ADR implements was drafted with `POST /oauth/register`
public and unauthenticated (per RFC 7591) and **no check on which
`redirect_uris` it would sign a token for**. That's a real
vulnerability: anyone could register a `client_id` for an arbitrary
`redirect_uri`, including an attacker-controlled one, and — because
`/oauth/authorize` trusted whatever `redirect_uri` matched the
token's own claim — get an authorization code (or, on user
mis-click/phishing, a completed login) redirected to a domain bflow-mcp
never chose. Cognito's *lack* of DCR had been accidentally preventing
exactly this since ADR-0001; adding DCR without a check reopens it.

**Fix:** `RedirectUriAllowlist` — `POST /oauth/register` only signs a
`client_id` for a `redirect_uri` that exact-matches a config-driven
allowlist (`bflow.mcp.proxy.allowed-redirect-uris`). `/oauth/authorize`
re-checks the allowlist too, not just the token's own claim, so
removing a platform from config invalidates its previously-issued
long-lived `client_id` tokens immediately rather than waiting a year
for them to expire on their own.

### Exact match, not a domain/wildcard allowlist

Considered a domain-suffix rule (e.g. `*.claude.ai`) instead of exact
URIs. Rejected: every platform's callback is one fixed, documented URL
with no dynamic path — MCP Inspector, ChatGPT, and Claude.ai included
(mirrors `CALLBACK_URLS` in `infra/17-mcp-cognito-client.sh`, which
this list is meant to track). A domain rule would accept any path
under that host for no onboarding benefit, since the real URI never
varies. Exact match costs the same one config line per platform and
leaves no room for a same-domain open redirect on the platform's own
site to be abused against bflow-mcp.

## Consequences

### Positive

- Onboarding a new platform is now a one-line config change
  (`bflow.mcp.proxy.allowed-redirect-uris`) plus redeploy — no more
  hand-running an AWS CLI script against Cognito for that step.
  `infra/17-mcp-cognito-client.sh` still exists and still needs running
  if bflow-mcp's own public URL changes (its callback is the one
  Cognito-facing entry every DCR client now funnels through).
- Still zero new database, new or shared — every claim from ADR-0009/
  0010 that motivated the original proxy-not-authorization-server
  design continues to hold.
- A `client_id` token being long-lived (1 year) is bounded by the
  allowlist re-check in `/oauth/authorize`, not just by its own
  expiry — revoking a platform is a config change, not a wait.

### Negative

- The allowlist is still hand-maintained config, just in one fewer
  place (`application.yml`/env var instead of also touching Cognito).
  A platform whose callback URL changes still needs a human to update
  it and redeploy.
- `POST /oauth/register` remains public and unauthenticated by design
  (RFC 7591), so it's a standing target for the allowlist check to
  keep working correctly — any future change to `RedirectUriAllowlist`
  or `OAuthProxyController#authorize` needs to preserve the
  double-check (registration time AND authorize time), not just one.