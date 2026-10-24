# MCP for your finances

Give AI assistants controlled access to your personal finances.

bflow-mcp is an open-source Model Context Protocol server that
allows AI assistants to securely interact with financial data
through the [BFlow](https://github.com/DiazzzDev/BFlow-Financial-Engine) API.

![License](https://img.shields.io/badge/license-MIT-blue)
![Status](https://img.shields.io/badge/status-alpha-orange)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-8A2BE2)

> **Requires a BFlow account.** bflow-mcp doesn't create accounts or
> store your data — it's a bridge between an AI assistant and the
> [BFlow](https://www.bflow-studio.com/) account you already have.

## What can you do?

Ask an AI assistant to:

- See your wallets, categories and budgets
- Query your transaction history
- Get a summary of this month's spending
- Add or correct expenses and income
- Create budgets
- Set up recurring transactions (subscriptions, paychecks)

### Example

> **You:** How much did I spend on food this month?
>
> **Assistant:** Let me check your BFlow wallets...
>
> You've spent **$142.30** on food this month across 2 transactions
> in your "Personal" wallet. That's about 18% of your total spending
> so far.

No copy-pasting statements, no spreadsheets — the assistant asks BFlow
directly, on your behalf, and only for what it needs to answer you.

## How it works

```
You  →  AI Assistant  →  bflow-mcp  →  BFlow API  →  your data
```

You talk to your AI assistant like you normally would. When it needs
something from BFlow, it asks `bflow-mcp`, which forwards that request
to the real BFlow API **using your own login** — never a shared or
generic account. BFlow decides what you're allowed to see or change,
exactly as if you'd done it yourself in the app.

## Quick Start

### Hosted

Add this URL as a custom connector in your AI assistant (in Claude, from the
connectors settings):

```
https://mcp.bflow-studio.com/mcp
```

You'll be asked to log in with your BFlow account the first time — the
same login (including Google) you already use for BFlow.

### Self-hosted

```bash
git clone https://github.com/DiazzzDev/bflow-mcp.git
cd bflow-mcp
cp .env.example .env
# fill in .env with your BFlow API URL and Cognito issuer
docker compose up --build
```

Then add it as a server in your AI assistant's settings, pointing at:

```
http://localhost:8081/mcp
```

## Available Tools

10 tools, each tied to one permission (scope) that the assistant must be
granted. Read tools run immediately; write tools always ask you to confirm
first (see [Security](#security)).

### Read

| Tool | Scope | Description |
|------|-------|-------------|
| `listWallets` | `bflow-mcp/wallets.read` | Wallets you own or belong to |
| `listCategories` | `bflow-mcp/categories.read` | Transaction categories with id, name, type, icon and color — used to pick a category when creating transactions or budgets |
| `listTransactions` | `bflow-mcp/transactions.read` | Your expenses, incomes and transfers across all wallets |
| `listBudgets` | `bflow-mcp/budgets.read` | Your budgets across all wallets |
| `listRecurring` | `bflow-mcp/recurring.read` | Scheduled recurring expenses and incomes |
| `getSpendingSummary` | `bflow-mcp/dashboard.read` | This month's spending summary across all wallets |

### Write (require confirmation)

| Tool | Scope | Description |
|------|-------|-------------|
| `createTransaction` | `bflow-mcp/transactions.write` | Adds a one-off expense or income. Safe to retry with an idempotency key |
| `updateTransaction` | `bflow-mcp/transactions.write` | Replaces an existing expense or income entry |
| `createBudget` | `bflow-mcp/budgets.write` | Creates a budget for one wallet (daily, weekly or monthly) |
| `createRecurringTransaction` | `bflow-mcp/recurring.write` | Creates a recurring expense or income schedule; BFlow generates the transactions |

Not available on purpose: money transfers, payments, deletions and anything
touching your subscription or account settings.

## Security

- **Your login, not ours.** Every request is made as you, through
  your own BFlow account — bflow-mcp never sees your password, and
  can't act as anyone else.
- **Scoped by design.** An assistant only gets the specific
  permissions you approve (e.g. "read my wallets") — never blanket
  access to your account.
- **You confirm every change.** Write tools first return a preview of
  exactly what would be sent to BFlow; nothing happens until the assistant
  shows it to you and calls again with your confirmation.
- **Safe retries.** Creating a transaction accepts an idempotency key, so a
  retry after an unclear result doesn't duplicate the entry.
- **Nothing risky, automatically.** Sensitive actions like money
  transfers and payments are intentionally left out of what an AI assistant can
  ever do here.
- **Open source.** The code is public — you (or anyone) can read
  exactly what it does before trusting it with financial data.

## Architecture

See [`ARCHITECTURE.md`](./ARCHITECTURE.md) for how a request actually
flows through this service, and [`docs/adr/`](./docs/adr) for
decisions specific to this repo (start with
[the Cognito/MCP compatibility ADR](./docs/adr/0001-oauth-proxy-for-cognito-mcp-incompatibilities.md)
if something OAuth-related breaks). The authorization model and
security boundaries this service implements are documented in
[`BFlow-Financial-Engine`'s ADR-0009 and ADR-0010](https://github.com/DiazzzDev/BFlow-Financial-Engine/tree/main/docs/adr).

## Infrastructure

AWS resources are managed with Terraform in [`infra/`](./infra/README.md). It
reads (never modifies) the ECS cluster, subnets and security group owned by
BFlow-Financial-Engine, and deploys run from `.github/workflows/deploy.yml`.

---

Looking to set this up for development instead? See
[DEVELOPMENT.md](./DEVELOPMENT.md).