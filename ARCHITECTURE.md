# Architecture

How a request actually moves through bflow-mcp, end to end. For *why*
each piece exists, see `docs/adr/` (this repo) and
`BFlow-Financial-Engine/docs/adr/0009-*.md` / `0010-*.md`.

## Request flow

```
AI Client
   │  Streamable HTTP (MCP)
   v
Cognito JWT validation (SecurityConfig)
   │
   v
Tool method call, wrapped by 3 AOP aspects, in this order:
   │
   ├─ ToolObservabilityAspect   (order -1, outermost)
   │     logs tool name, userId, duration, outcome — always,
   │     regardless of what happens inside
   │
   ├─ ToolErrorHandlingAspect   (order 0)
   │     catches ANY exception from what's inside it (including a
   │     denied scope) and turns it into {code, message, retryable}
   │     — never a raw stack trace reaches the client
   │
   ├─ RequiresScopeAspect       (order 1, innermost)
   │     checks the JWT's granted scopes against @RequiresScope
   │     before the tool body runs at all
   │
   v
Tool method body
   │
   ├─ (write tools only) ConfirmationGate.checkOrNull(...)
   │     if not yet confirmed=true, returns a CONFIRMATION_REQUIRED
   │     preview here and stops — nothing below this line runs
   │
   v
BflowApiClient (get/post/put)
   │  forwards the SAME bearer token, plus:
   │    X-BFlow-Channel: mcp
   │    X-BFlow-Actor-Type: AGENT
   │    X-Correlation-Id: <fresh UUID per call>
   │    Idempotency-Key: <caller-supplied or fresh UUID, POST only>
   v
BFlow API (separate repo, separate deploy)
   │  resolves the user from the SAME JWT, does its own resource-level
   │  authorization (WalletUser/WalletRole — unrelated to bflow-mcp),
   │  and — since BFlow-MCP's audit work — writes an AuditRecord
   │  tagging this as actorType=AGENT, source=MCP
   v
Response flows back up through the same 3 aspects (observability logs
the outcome, error handling translates any failure) to the AI client.
```

## Why the aspect order matters

`ToolObservabilityAspect` has to be the true outermost layer so it logs
*every* call, including ones the scope gate denies — otherwise a
pattern of repeated scope denials would be invisible. `ToolErrorHandlingAspect`
has to wrap `RequiresScopeAspect`, not the other way around, so a
denied scope comes back as a normal `{code: "SCOPE_DENIED", ...}` tool
result instead of an unhandled exception. Get this order backwards and
a scope denial either goes unlogged or crashes instead of returning a
clean response — there's no compiler check for this, only the explicit
`@Order`/`setOrder()` values in each aspect.

## The OAuth layer, separately

Authentication (getting a token in the first place) is a distinct
concern from everything above, which only runs once a valid token is
already presented. See `docs/adr/0001-oauth-proxy-for-cognito-mcp-incompatibilities.md`
for the full story — short version: `OAuthProxyController` and
`AuthorizationServerMetadataController` exist entirely to work around
things Cognito doesn't support that the MCP spec expects, and are
permanent, not temporary.

## Adding a new tool

1. Does it read or write? Pick the matching scope from the catalog in
   `infra/17-mcp-cognito-client.sh` — or add a new one there if none fits.
2. Write the tool class in `src/main/java/bflow/mcp/tools/`, following
   any existing one: inject `BflowApiClient`, call the real BFlow
   endpoint, annotate with `@Tool` and `@RequiresScope`.
3. If it's a write, inject `ConfirmationGate` too and call
   `checkOrNull(confirmed, ACTION_NAME, previewMap)` before executing —
   every write tool does this, no exceptions.
4. Register it in `ToolRegistrationConfig`.
5. Nothing else changes — the AOP chain, error handling, and
   observability all apply automatically to any `@Tool`-annotated method.