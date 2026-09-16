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
> BFlow account https://www.bflow-studio.com/ you already have.

## What can you do?

Ask an AI assistant to:

- View your wallets
- Query transactions
- Create expenses and income
- Manage budgets
- Manage recurring transactions
- Analyze your financial activity
- Automate your daily finances
- Use AI alongside the BFlow platform you already use.

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

bflow-mcp isn't published to a public MCP directory yet — for now,
run it yourself:

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

You'll be asked to log in with your BFlow account the first time — the
same login (including Google) you already use for BFlow.

## Available Tools

| Tool | Scope | Status | Description |
|------|-------|--------|-------------|
| `listWallets` | `bflow-mcp/wallets.read` | Available | Lists the wallets you own or belong to |
| Query transactions | `bflow-mcp/transactions.read` | Planned | View your transaction history |
| Create expense/income | `bflow-mcp/transactions.write` | Planned | Add a new transaction |
| View budgets | `bflow-mcp/budgets.read` | Planned | Check budget status and spending |
| Manage budgets | `bflow-mcp/budgets.write` | Planned | Create or update a budget |
| Manage recurring items | `bflow-mcp/recurring.read`, `bflow-mcp/recurring.write` | Planned | View or manage recurring transactions |

This project is early — one tool is live today, the rest are actively
being built. Follow the repo for updates.

## Security

- **Your login, not ours.** Every request is made as you, through
  your own BFlow account — bflow-mcp never sees your password, and
  can't act as anyone else.
- **Scoped by design.** An assistant only gets the specific
  permissions you approve (e.g. "read my wallets") — never blanket
  access to your account.
- **Nothing risky, automatically.** Sensitive actions like money
  transfers are intentionally left out of what an AI assistant can
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

---

Looking to set this up for development instead? See
[DEVELOPMENT.md](./DEVELOPMENT.md).