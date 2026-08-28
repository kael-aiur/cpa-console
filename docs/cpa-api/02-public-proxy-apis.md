# Client-Facing Proxy APIs

These APIs forward CLI/model traffic to upstream providers. They normally use a client API key, not the Management key.

## Basic Routes

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/healthz` | Returns `{"status":"ok"}` |
| `HEAD` | `/healthz` | Liveness check returning empty body |
| `GET` | `/` | Returns service name and representative endpoint list |
| `GET` | `/management.html` | Serves/downloads bundled management panel if enabled |

## OpenAI-Compatible Endpoints

Prefix: `/v1`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/models` | Unified model catalog across registered providers |
| `POST` | `/chat/completions` | OpenAI Chat Completions proxy |
| `POST` | `/completions` | Legacy Completions proxy |
| `POST` | `/images/generations` | Image generation |
| `POST` | `/images/edits` | Image edit |
| `POST` | `/videos` | xAI-compatible video generation alias |
| `POST` | `/videos/generations` | Video generation |
| `POST` | `/videos/edits` | Video edit |
| `POST` | `/videos/extensions` | Video extension operation |
| `GET` | `/videos/{request_id}` | Retrieve video job/result by request ID |
| `POST` | `/responses` | OpenAI Responses API |
| `GET` | `/responses` | Responses WebSocket upgrade |
| `POST` | `/responses/compact` | Compact Responses operation |
| `POST` | `/alpha/search` | Codex Alpha Search |
| `POST` | `/live` | Codex Live entrypoint |
| `GET` | `/live/{call_id}` | Codex Live sideband channel |

Streaming responses preserve their respective protocol semantics.

## Claude-Compatible Endpoints

Prefix: `/v1`

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/messages` | Anthropic Messages proxy |
| `POST` | `/messages/count_tokens` | Anthropic token-count proxy |

The same requests accept Claude-style authentication, particularly `x-api-key`.

## Dedicated Video Aliases

Prefix: `/openai/v1`

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/videos` | Create video job |
| `GET` | `/videos/{video_id}` | Retrieve video metadata/status |
| `GET` | `/videos/{video_id}/content` | Download/retrieve video content |

## Codex Direct Aliases

Prefix: `/backend-api/codex`

These aliases support clients configured with a Codex `chatgpt_base_url`.

| Method | Path | Equivalent |
| --- | --- | --- |
| `POST` | `/responses` | `/v1/responses` |
| `GET` | `/responses` | `/v1/responses` WebSocket |
| `POST` | `/responses/compact` | `/v1/responses/compact` |
| `POST` | `/alpha/search` | `/v1/alpha/search` |

## Gemini-Compatible Endpoints

Prefix: `/v1beta`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/models` | Unified Gemini-format model list |
| `POST` | `/interactions` | Google Interactions API entrypoint |
| `POST` | `/models/{action...}` | Provider dispatch for Gemini generation/count-token-like actions |
| `GET` | `/models/{action...}` | Provider dispatch for retrievable model actions |

The wildcard `{action...}` is dispatched by the Gemini handler based on the remaining model/action path.

## OAuth Redirect Receivers

Browser/provider redirects land here before completion of Management-initiated flows.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/anthropic/callback` | Store short-lived callback data for pending Anthropic session |
| `GET` | `/codex/callback` | Same for Codex |
| `GET` | `/antigravity/callback` | Same for Antigravity |

Query parameters handled include `state`, `code`, `error`, and `error_description`. These callbacks return an HTML success page, not JSON.
