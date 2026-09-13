# bflow-mcp

Remote MCP server exposing [BFlow](https://github.com/DiazzzDev/BFlow-Financial-Engine)
to AI agents. See `BFlow-Financial-Engine/docs/adr/0009-*.md` and
`0010-*.md` for the authorization model and security boundaries this
service implements.

## What this is (and isn't)

- A separate Spring Boot application. It has **no** dependency on
  `BFlow-Financial-Engine`'s Maven module, domain classes, repositories,
  or database.
- Its only relationship to BFlow is being an HTTPS client of its public
  API (`bflow.api.base-url`), forwarding the calling user's own bearer
  token on every request.
- Resource authorization (can this user touch this wallet?) is decided
  entirely by the BFlow API, unchanged. This service only gates *which
  kind* of operation an agent's token is scoped to attempt
  (`@RequiresScope`).

## Run it locally

Requires Java 21 (Maven wrapper included, no local Maven needed).

```bash
cp .env.example .env
# edit .env: point BFLOW_API_BASE_URL at your local BFlow instance
# (usually http://host.docker.internal:8080) and COGNITO_ISSUER_URI
# at the same Cognito user pool BFlow's API uses.

./mvnw spring-boot:run
```

Or via Docker Compose:

```bash
docker compose up --build
```

Or open this folder in VS Code / any devcontainer-compatible editor —
`.devcontainer/` is preconfigured (Java 21, Maven, port 8081 forwarded).

> I generated this scaffold without access to Maven Central from the
> sandbox that built it, so `./mvnw clean install` has **not** been run
> against real dependencies yet. Run it yourself as the first step —
> if `spring-ai-starter-mcp-server-webmvc:1.1.2` has moved on since,
> that's the one coordinate to double check in `pom.xml`.

## Project layout

```
bflow-mcp/
├── .devcontainer/         Dev container: Java 21 + Maven, no DB/Redis needed
├── config/checkstyle/     Same ruleset as BFlow-Financial-Engine (copied, kept in sync by hand)
├── docker-compose.yml     Standalone run (outside the devcontainer)
├── ecs/                   ECS Fargate task definition template for this service
├── infra/
│   └── 16-mcp-dns-sync.sh Adds this service to BFlow's existing Cloudflare
│                          dynamic-DNS Lambda as a second target — depends on
│                          BFlow-Financial-Engine/infra having been bootstrapped
│                          first (reuses its dns-sync binary and outputs.env)
├── src/main/java/bflow/mcp/
│   ├── McpServerApplication.java
│   ├── client/
│   │   └── BflowApiClient.java     The only class that talks to BFlow's API
│   ├── security/
│   │   ├── SecurityConfig.java     Stateless JWT resource server (same Cognito pool)
│   │   ├── RequiresScope.java      Scope annotation
│   │   └── RequiresScopeAspect.java  Enforces it against SCOPE_* authorities
│   └── tools/
│       ├── ListWalletsTool.java        First tool: wallets:read → GET /api/v1/wallets
│       └── ToolRegistrationConfig.java Registers tool beans with the MCP server
└── src/test/java/bflow/mcp/
    └── McpServerApplicationTests.java  Context-load smoke test
```

## Adding a new tool (issues #9-#17)

1. Add the scope to BFlow's Cognito app client / consent screen if it's
   new (see ADR-0009 §5 — still open).
2. Write the tool class in `tools/`, following `ListWalletsTool`: inject
   `BflowApiClient`, call the existing BFlow endpoint, annotate the
   method with `@Tool` and `@RequiresScope`.
3. Add it to `ToolRegistrationConfig#bflowTools`.
4. No changes needed anywhere else — the scope gate and BFlow's own
   resource-level authorization both apply automatically.

## Deploying

Same shape as `BFlow-Financial-Engine`: build the image, push to ECR,
register a new revision of `ecs/task-definition.template.json`, update
the `bflow-mcp` ECS service. A GitHub Actions workflow mirroring
`BFlow-Financial-Engine/.github/workflows/deploy.yml` still needs to be
written for this repo (not included in this scaffold).

This service shares BFlow's existing ECS cluster, VPC, and security
group — no new cluster. It only needs its own port (8081) opened on that
security group and its own Cloudflare DNS record; see
`infra/16-mcp-dns-sync.sh` and the one-line addition to
`BFlow-Financial-Engine/infra/bootstrap/05-security-groups.sh`.
