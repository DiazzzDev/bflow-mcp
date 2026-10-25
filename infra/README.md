# bflow-mcp infrastructure

Terraform is the single source of truth for bflow-mcp's AWS infrastructure. It
replaces the numbered AWS CLI scripts (`16`–`20`), `lib/helpers.sh` and their
implicit `config.env` / `outputs.env` state. Nothing is provisioned from bash
anymore: run plain `terraform` from `infra/terraform` (no wrapper scripts to
maintain).

## Who owns what

bflow-mcp runs on the monolith's platform, so the two Terraform stacks must
coexist and the pre-revenue footprint stays minimal.

| Owned by BFlow-Financial-Engine (only **read** here) | Owned by this stack |
|---|---|
| ECS cluster, public subnets, **ECS security group** | ECR repository, ECS log group |
| Security group ingress (port 8081 via `ecs_ingress_rules`) | ECS execution and task roles |
| GitHub OIDC provider | GitHub Actions deploy role |
| EventBridge rule `bflow-ecs-task-state-change` | DNS-sync Lambda, its role, secret container, log group and target on that rule |
| SNS topic `bflow-alerts`, alarms, dashboard, Transaction Search (`observability.tf`) | Cognito resource server, app client and branding (opt-in) |
| Go source of the DNS-sync Lambda | Optional `xray:PutTraceSegments` on the task role (opt-in) |

Consequences:

- This stack never declares security groups, rules, subnets, clusters, the OIDC
  provider, event rules or SNS topics (CI enforces it). If the MCP port or a
  Cloudflare range changes, change `ecs_ingress_rules` in the monolith.
- Shared resources are looked up by name (`shared_*` variables): no
  `terraform_remote_state` coupling between the local states, and no hand-copied
  ids (the old `config.env`).
- The ECS service and task definition are deployed by `deploy.yml`, not
  Terraform, exactly like the monolith.
- No NAT gateway, ALB, extra cluster or extra security group.

## Layout

| File | Contents |
|---|---|
| `shared.tf` | Data sources for everything owned by the monolith |
| `platform.tf` | ECR, log group, ECS roles, optional OTel trace permission (`otel_traces_enabled`) |
| `github-oidc.tf` | Deploy role and its minimal policy |
| `cognito.tf` | Resource server, app client, Managed Login branding (`manage_cognito`) |
| `dns-sync.tf` | Secret container, Lambda, role, log group, EventBridge target (`dns_sync_enabled`) |
| `outputs.tf` | `github_actions_role_arn`, `deployment_variables` |

Secret **values** never enter Terraform state: put the Cloudflare credentials
in the secret container with the AWS CLI or console.

## Observability (OTel)

The monolith owns everything account-wide (Transaction Search, the `bflow-alerts`
SNS topic, alarms, dashboard). This stack only adds what is specific to the MCP
task:

1. `otel_traces_enabled = true` attaches `xray:PutTraceSegments` to
   `bflow-mcp-ecs-task-role` (same single permission the monolith uses).
2. The agent is switched on from `deploy.yml` with the same variables as the
   monolith (`JAVA_TOOL_OPTIONS`, `OTEL_*`, endpoint
   `https://xray.<region>.amazonaws.com/v1/traces`), with a different
   `OTEL_SERVICE_NAME`. That needs the ADOT jar in this repo's Dockerfile.
3. Task failure alerts: the monolith's `bflow-ecs-task-failed` rule only matches
   `service:bflow-service`. To cover the MCP, add `"service:bflow-mcp"` to the
   `group` list of that rule in the monolith (one line; the SNS policy already
   trusts that rule). Do not create a second topic or rule here.
4. The Lambda's log group is managed here with 7-day retention
   (`dns_sync_log_retention_days`), mirroring the monolith's.

## First migration (import existing resources)

0. Make sure `terraform` is available in your devcontainer.
1. `cp terraform/terraform.tfvars.example terraform/terraform.tfvars` and verify
   every name against the live account. Names that the old scripts derived from
   `PROJECT_NAME` (`dns_sync_function_name`, `dns_sync_role_name`,
   `dns_sync_secret_name`) are the most likely to differ. The Lambda is
   currently called `bflow-mcp-mcp-dns-sync` (doubled `mcp`); keep it, renaming
   would recreate it.
2. `cp terraform/imports.local.tf.example terraform/imports.local.tf` and fill
   in ids from AWS CLI discovery (both files are ignored by Git).
3. `cd terraform && terraform init -upgrade` (the stack needs AWS provider
   >= 6.12; the flag refreshes an older `.terraform.lock.hcl`), then
   `terraform validate`.
4. `terraform plan`. Imports are listed as "to import"; every resource must
   show no create, update or destroy. Anything else means the code or the
   import ids differ from the live infrastructure: fix them, do not apply over
   it. Before enabling `manage_cognito`, compare the plan with the live app
   client (`aws cognito-idp describe-user-pool-client`): the bash script only
   set some attributes, so Terraform may want to change the rest.
5. `terraform apply` (with no changes it only records the imports in state),
   then `terraform plan` again: it must report "No changes". Copy
   `terraform output deployment_variables` and `terraform output
   github_actions_role_arn` into the GitHub `production` environment and
   compare them with what is there.
6. Delete `imports.local.tf` (the imports are in state now) and the old files:
   `16`–`20` scripts, `lib/helpers.sh`, `config.env`, `outputs.env`,
   `mcp-cognito.env`, `mcp-cloudflare.env` and `*.env.example`.

Day to day: `terraform plan` / `terraform apply` / `terraform fmt -recursive` /
`terraform destroy` (it only removes what this stack owns; shared resources are
read-only here).

### DNS sync Lambda

There is a single Go source for the DNS-sync Lambda, in
`BFlow-Financial-Engine/infra/dns-sync`. This repo does not carry a copy: build
the zip there and set `dns_sync_zip_path` to it.

```bash
cd BFlow-Financial-Engine/infra/dns-sync
mkdir -p .build
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o .build/bootstrap .
(cd .build && zip -q lambda_function.zip bootstrap)
```

Enabling it also requires the monolith's `dns_sync_enabled = true` (it owns the
EventBridge rule). Rebuilding the zip changes its hash, so the next plan shows
the Lambda being updated with identical code; that is expected.

## What `terraform plan` does not check

A no-op plan proves that everything this stack declares matches what exists.
It cannot see:

- IAM policies attached to the roles that the `.tf` files do not declare. List
  them with `aws iam list-attached-role-policies` and `aws iam list-role-policies`
  for each role; they keep working but are not codified.
- Resources owned by the monolith that this service depends on. In particular,
  port 8081 must be in the monolith's `ecs_ingress_rules`: if it is missing, the
  monolith's next apply removes the rule and the MCP becomes unreachable.
- The ECS service and task definition, which `deploy.yml` manages.

## Keeping things in sync

- Cognito scopes live in `variables.tf` (`cognito_scopes`) and in the Java
  `SCOPES_SUPPORTED` list. `infra-ci.yml` fails the PR if they differ.
- New AI platforms are onboarded in `BFLOW_MCP_PROXY_ALLOWED_REDIRECT_URIS`
  (DCR allowlist), not in `cognito_extra_callback_urls`, which only covers
  clients that present the real Cognito client id. A platform that identifies
  itself with a Client ID Metadata Document is also listed, by its `client_id`
  URL, in `BFLOW_MCP_PROXY_CIMD_ALLOWED_CLIENT_IDS` (see ADR-0004).

## CI/CD

Pull requests touching `infra/` run `infra-ci.yml`: `terraform fmt`,
`terraform validate` and guard rails (no shared resources declared, opt-ins
default to off, scopes in sync) with no AWS credentials. Apply remains a
deliberate operator action, as in the monolith.