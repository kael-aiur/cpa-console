# Realtime, Live and Sideband APIs

Realtime authentication supports either standard client API-key authentication or a Codex Live client secret, depending on route middleware.

| Method | Path | Middleware | Handler/Purpose |
| --- | --- | --- | --- |
| `GET` | `/v1/realtime` | realtime auth | Realtime WebSocket upgrade |
| `POST` | `/v1/realtime` | realtime auth | Generic Live/Realtime call entrypoint |
| `POST` | `/v1/realtime/calls` | realtime auth | Create/dispatch a Live call |
| `GET` | `/v1/realtime/calls/{call_id}` | realtime auth | Attach to Live sideband |
| `POST` | `/v1/realtime/client_secrets` | standard auth | Create Realtime client secret |
| `POST` | `/v1/realtime/sessions` | standard auth | Create legacy Realtime session |
| `POST` | `/v1/realtime/transcription_sessions` | standard auth | Create transcription session |
| `GET` | `/v1/realtime/translations` | realtime auth | Translation WebSocket/stream setup |
| `POST` | `/v1/realtime/translations` | realtime auth | Translation request |
| `POST` | `/v1/realtime/translations/client_secrets` | standard auth | Translation-specific secret flow |
| `POST` | `/v1/realtime/calls/{call_id}/hangup` | standard auth | SIP call hangup/control |
| `POST` | `/v1/realtime/calls/{call_id}/accept` | standard auth | SIP accept/control |
| `POST` | `/v1/realtime/calls/{call_id}/reject` | standard auth | SIP reject/control |
| `POST` | `/v1/realtime/calls/{call_id}/refer` | standard auth | SIP refer/control |

Related non-Realtime Codex routes are documented in [02-public-proxy-apis.md](./02-public-proxy-apis.md).

Implementation source: `sdk/api/handlers/openai` and `internal/client/codex/live`; registration is in `internal/api/server_routes.go`.
