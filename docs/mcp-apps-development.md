# MCP Apps: spec audit and local host

This note targets the stable MCP Apps protocol revision `2026-01-26`.
The three UI resources are deliberately small, inline HTML documents. They
use the minimum protocol bridge needed by BFlow rather than bundling
`@modelcontextprotocol/ext-apps`: adopting `App` would add a JavaScript build
and generated asset pipeline to a Java-only server, while these views need
only initialization, tool notifications, context/theme updates, and sizing.
No runtime dependency or CDN request is added. If a view starts using more
protocol features, revisit the official SDK instead of extending this bridge
indefinitely.

## Messages and lifecycle fields

The reference is `specification/2026-01-26/apps.mdx`, especially
**App Capabilities in `ui/initialize`**, **Host Context in
`McpUiInitializeResult`**, **MCP Apps Specific Messages → Notifications**,
**Lifecycle → UI Initialization**, and **Data Passing**. The corresponding
generated SDK schema makes the initialization request's required fields
explicit: `appInfo` (name/version), `appCapabilities`, and `protocolVersion`.
References: [stable MCP Apps specification](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
and [generated initialization message schemas](https://github.com/modelcontextprotocol/ext-apps/blob/main/src/generated/schema.ts#L979-L1020).

| Message | Direction and exact JSON-RPC shape |
| --- | --- |
| `ui/initialize` | View request: `{jsonrpc:"2.0", id, method:"ui/initialize", params:{appInfo:{name,version}, appCapabilities:{experimental?,tools?:{listChanged?},availableDisplayModes?}, protocolVersion}}`. The response matches the request `id` and has `result:{protocolVersion,hostInfo:{name,version},hostCapabilities,hostContext}`. `hostContext` includes optional `theme`, `styles`, `containerDimensions`, locale/platform and other context fields. |
| `ui/notifications/initialized` | View notification after the matching initialize result: `{jsonrpc:"2.0", method:"ui/notifications/initialized", params?}`. Notifications have no `id`. |
| `ui/notifications/tool-input` | Host notification after initialization: `{jsonrpc:"2.0", method:"ui/notifications/tool-input", params:{arguments:Record<string,unknown>}}`. No `id`; required before `tool-result`. |
| `ui/notifications/tool-result` | Host notification after tool completion: `{jsonrpc:"2.0", method:"ui/notifications/tool-result", params:CallToolResult}`. `CallToolResult` carries `content` and optional `structuredContent`, `_meta`, and `isError`. No `id`. |
| `ui/notifications/size-changed` | View notification: `{jsonrpc:"2.0", method:"ui/notifications/size-changed", params:{width:number,height:number}}`. No `id`; the View SHOULD report body-size changes. |
| `ui/notifications/host-context-changed` | Host notification: `{jsonrpc:"2.0", method:"ui/notifications/host-context-changed", params:Partial<HostContext>}`. No `id`; merge partial updates, including theme changes. |

`ui/initialize` and `tools/call` are requests and carry IDs. The four
`ui/notifications/*` messages above are notifications and must not carry an
ID. The Host waits for `ui/notifications/initialized` before sending tool
input/result. The views now apply theme both from the initialize result and
later partial context updates, and report dimensions with `ResizeObserver`.

## What was wrong in the original views

All three HTML files sent `ui/initialize` with `{capabilities:{}}`, omitting
`appInfo`, `appCapabilities`, and the required `protocolVersion`. Their
shared emitters assigned IDs to every message, including
`ui/notifications/initialized`. They sent no `size-changed`. The initialize
response was detected by any truthy `id`, rather than matching the initialize
request. Theme was only read from that response, not later context-change
notifications. The Playwright host used to respond with only `hostContext`
and did not enforce any of these rules or gate input/results on initialized.

The Playwright harness now checks the required initialize fields, no-ID
notifications, size reporting and host data ordering; it responds with a full
initialize result. A frozen pre-fix budgets resource in
`src/test/resources/mcp-ui/budgets-legacy.html` is used by a negative test to
show the old contract violates the harness.

## Local run with the official basic-host

The MCP Apps reference `basic-host` uses Streamable HTTP and is useful for
checking resource loading, iframe isolation, lifecycle and sizing. It is not
a substitute for testing every real host.

1. Start bflow-mcp from this repository in a local-only profile. PowerShell:

   ```powershell
   $env:SPRING_PROFILES_ACTIVE = 'mcp-apps-dev'
   $env:BFLOW_MCP_PROXY_SIGNING_SECRET = 'local-only-secret-at-least-32-bytes'
   $env:BFLOW_MCP_PROXY_ALLOWED_REDIRECT_URIS = 'http://localhost/callback'
   $env:COGNITO_ISSUER_URI = 'http://127.0.0.1/mcp-apps-dev-only'
   $env:COGNITO_HOSTED_UI_DOMAIN = 'http://127.0.0.1'
   $env:COGNITO_APP_CLIENT_ID = 'local-test-client'
   mvn spring-boot:run
   ```

   The profile binds only to `127.0.0.1` and accepts only the synthetic
   bearer token `bflow-mcp-apps-local`. The decoder bean is excluded whenever
   `prod` or `production` is active, and it refuses startup if the bind address
   is not loopback, and startup fails if any other Spring profile is active
   with it. Do not add this profile to any deployment configuration.
   Its CORS policy permits only the basic-host origin
   `http://localhost:8080` for `/mcp`.

2. Clone the official `modelcontextprotocol/ext-apps` repository at the
   version used by its basic-host lockfile (currently `v2.0.3`), install the
   repository dependencies, then enter `examples/basic-host`.

   ```powershell
   git clone --branch v2.0.3 --depth 1 https://github.com/modelcontextprotocol/ext-apps.git ext-apps
   cd ext-apps
   npm install
   cd examples/basic-host
   npm install
   ```

3. The current basic-host source creates `StreamableHTTPClientTransport`
   without Authorization headers. In `examples/basic-host/src/implementation.ts`,
   replace `new StreamableHTTPClientTransport(serverUrl)` with:

   ```typescript
   new StreamableHTTPClientTransport(serverUrl, {
     requestInit: {
       headers: { Authorization: "Bearer bflow-mcp-apps-local" },
     },
   })
   ```

   This change belongs only in the throwaway ext-apps clone; do not add the
   fake token to a shared host configuration.

4. In the basic-host terminal, set its MCP URL and start it:

   ```powershell
   $env:SERVERS = '["http://127.0.0.1:8081/mcp"]'
   npm run start
   ```

   Open `http://localhost:8080`, connect to the listed server and call
   `listBudgets` or `openTransactionForm`. The fake token lets bflow-mcp
   authenticate and authorize local tool requests; it is forwarded unchanged
   to BFlow's API, so a real backend will reject it. Use the host to verify UI
   negotiation/rendering and error states, or point `BFLOW_API_BASE_URL` at a
   local API stub that accepts this synthetic identity to exercise populated
   results. Do not use the token against a production API.

The test suite has a narrower, self-contained Playwright host that exercises
rendering with controlled tool results and checks the wire contract without
network dependencies.
