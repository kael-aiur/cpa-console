# CPA API Reference

This directory documents the HTTP interfaces exposed by the upstream [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) service that this console will manage.

Source reviewed: `/Users/kael/workspace/github/CLIProxyAPI`.

## Documents

| Document | Scope |
| --- | --- |
| [`01-server-and-auth.md`](./01-server-and-auth.md) | Base URL, CORS, client API keys, Management API authentication, common errors |
| [`02-public-proxy-apis.md`](./02-public-proxy-apis.md) | Health, root, OpenAI-compatible, Claude, image/video, Gemini-compatible and Codex direct routes |
| [`03-realtime-and-live-apis.md`](./03-realtime-and-live-apis.md) | Codex Live and Realtime/WebSocket/SIP-related endpoints |
| [`04-management-core.md`](./04-management-core.md) | Management config, version, diagnostics and generic request tool |
| [`05-management-provider-keys.md`](./05-management-provider-keys.md) | Client API keys and provider credential lists |
| [`06-management-auth-files.md`](./06-management-auth-files.md) | Credential/auth-file lifecycle and OAuth login flows |
| [`07-management-models-routing-quota.md`](./07-management-models-routing-quota.md) | Models, routing, quota and usage |
| [`08-management-plugins.md`](./08-management-plugins.md) | Installed plugins and plugin store |
| [`09-management-logs.md`](./09-management-logs.md) | Runtime logs, request logs and usage queue |

## Important Route Prefixes

| Prefix / Path | Purpose |
| --- | --- |
| `/healthz` | Server liveness check |
| `/` | Service metadata/root index |
| `/management.html` | Bundled legacy management panel |
| `/v1`, `/openai/v1`, `/backend-api/codex` | Client-facing inference/API routes |
| `/v1beta` | Gemini-compatible client routes |
| `/anthropic/callback`, `/codex/callback`, `/antigravity/callback` | Provider OAuth redirect receivers |
| `/v0/management/**` | Console-facing Management API |

The implementation registers fixed routes in:

- `internal/api/server_routes.go`
- `internal/api/server_management.go`

Plugins may register additional Management routes at runtime under `/v0/management`; therefore clients must treat unknown management paths as possible plugin extensions.
