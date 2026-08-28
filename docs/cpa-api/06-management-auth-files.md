# Auth Files and OAuth Credentials

Base prefix: `/v0/management`

Auth files combine stored credential files with live in-memory auth state maintained by CPA.

## List Auth Files

```http
GET /v0/management/auth-files?name=<name>&auth_index=<id>
```

Both filters are optional. Exact matching checks either auth ID/file name and `auth_index`.

Successful entries contain commonly used console fields:

| Field | Description |
| --- | --- |
| `id` | Stable auth manager ID |
| `auth_index` | User-facing stable credential index |
| `name` | File name or fallback ID |
| `provider` / `type` | Provider/channel identifier |
| `label` | Display label, often email/project-derived |
| `status` | Runtime status enum/state |
| `status_message` | Status detail |
| `disabled` | Manual disable flag |
| `unavailable` | Temporary runtime availability indication |
| `runtime_only` | True if not represented by a regular disk file |
| `source` | `file` or `memory` |
| `path`, `size`, `modtime` | File details where applicable |
| `success`, `failed`, `recent_requests` | Basic success metrics and recent request buckets |
| `quota`, `model_quotas` | Passive quota observations when available |
| `email`, `project_id`, `account_type`, `account` | Provider account attributes when present |
| `created_at`, `updated_at`, `last_refresh`, `next_retry_after` | Timestamps where known |
| `priority`, `note`, `weight`, `websockets`, `request_retry` | Optional credential controls/metadata |

List response wrapper is `{ "files": [...] }`.

Disabled/runtime-only hidden records may be omitted.

## Retrieve Models for a Credential

```http
GET /v0/management/auth-files/models?name=<file-or-id>
```

Response:

```json
{
  "models": [
    {
      "id": "gemini-2.5-pro",
      "display_name": "Gemini 2.5 Pro",
      "type": "...",
      "owned_by": "..."
    }
  ]
}
```

Missing `name` yields `400`.

## Static Channel Model Definitions

```http
GET /v0/management/model-definitions/{channel}
```

Equivalent query form is also supported internally: `GET /model-definitions?channel=...`. Supported channels referenced by the source include `vertex`, `aistudio`, `antigravity`, `claude`, `codex`, `kimi`, and `xai`.

Response:

```json
{
  "channel": "claude",
  "models": []
}
```

Unknown channels return `400`.

## Download / Upload / Delete Credentials

### Download

```http
GET /v0/management/auth-files/download?name=<filename>.json
```

- Filename must end in `.json`.
- Downloads file attachment.
- Missing name/name-with-wrong extension yields `400`; absent file yields `404`.

### Upload Files

```http
POST /v0/management/auth-files
Content-Type: multipart/form-data
```

Accepts one or more files whose names end in `.json`. On partial failure, response code may be `207 Multi-Status`.

All-succeeded response resembles:

```json
{
  "status": "ok",
  "uploaded": 2,
  "files": ["/absolute/path/a.json", "/absolute/path/b.json"]
}
```

No uploaded files returns `400`.

### Upsert Raw JSON Credential

```http
POST /v0/management/auth-files?name=custom.json
Content-Type: application/json

{ ...credential JSON... }
```

Name must exist and end `.json`; invalid JSON/read/save errors return appropriate `4xx`/`5xx`.

### Delete

```http
DELETE /v0/management/auth-files?all=true
DELETE /v0/management/auth-files?name=a.json
DELETE /v0/management/auth-files?name=a.json&name=b.json&index=...
```

Source supports deleting all or multiple named files. Partial deletion can produce `207 Multi-Status`; successful simple deletion returns status `ok`.

## Enable/Disable Credential

```http
PATCH /v0/management/auth-files/status
Content-Type: application/json

{
  "name": "provider-email.json",
  "auth_index": "<optional-auth-index>",
  "disabled": true
}
```

`name` and `disabled` are required. Config-based API-key credentials may indirectly toggle an excluded-model pattern and return an extra `via` field.

## Patch Metadata Fields

```http
PATCH /v0/management/auth-files/fields
```

Body is a JSON object merged into the credential's persisted metadata. Selection includes normal target lookup (`name`/`auth_index`) inside the handler implementation. The endpoint updates arbitrary metadata fields allowed by auth storage and re-registers/persists the auth record.

## Vertex Service Account Import

```http
POST /v0/management/vertex/import
Content-Type: multipart/form-data
```

Form fields:

| Field | Required | Default |
| --- | --- | --- |
| `file` | Yes, service-account JSON | — |
| `location` | No | `us-central1` |

Validation extracts and requires `project_id`; `client_email` and location enrich the resulting record.

Response:

```json
{
  "status": "ok",
  "auth-file": "~/.cli-proxy-api/vertex-project-id.json",
  "project_id": "my-project",
  "email": "sa@my-project.iam.gserviceaccount.com",
  "location": "us-central1"
}
```

## OAuth Login Flow

Console flow recommended by implementation:

1. Start provider login with `is_webui=true` if browser callback forwarding should be enabled.
2. Receive authorization URL and opaque `state`.
3. Send user to authorization URL.
4. Poll `get-auth-status` until completion/error.
5. Cancel pending session with `oauth-session` when necessary.
6. Provider redirect callbacks arrive at public routes or forwarded callback ports; the front-end-style manual path is `oauth-callback`.

### Start Authorization URLs

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/anthropic-auth-url` | Anthropic/Claude OAuth/PKCE login |
| `GET` | `/codex-auth-url` | Codex/OpenAI OAuth/PKCE login |
| `GET` | `/antigravity-auth-url` | Antigravity/Google OAuth login |
| `GET` | `/kimi-auth-url` | Kimi OAuth/device/login flow |
| `GET` | `/xai-auth-url` | xAI OAuth login |

Optional common query parameter:

- `is_webui=true|1|yes|on` — starts local temporary forwarding server(s) so fixed legacy callback ports redirect into the main service callback route. Anthropic forwards port `54545`; Codex port `1455`.

Typical success response:

```json
{
  "status": "ok",
  "url": "https://provider.example/oauth/authorize?...",
  "state": "<opaque-state>"
}
```

Callers must retain `state`.

### Poll Login Status

```http
GET /v0/management/get-auth-status?state=<opaque-state>
```

HTTP remains `200`; semantic `status` distinguishes outcome:

```json
{"status": "wait"}
{"status": "ok"}
{"status": "error", "error": "Authentication failed"}
```

Without `state`, response is simply `{"status":"ok"}`.

### Callback Submission Endpoint

Two equivalent forms exist outside generic management middleware auth context differences (the post/get pair still sits behind availability middleware):

| Method | Path |
| --- | --- |
| `POST` | `/v0/management/oauth-callback` |
| `GET` | `/v0/management/oauth-callback` |

For POST:

```json
{
  "provider": "codex",
  "redirect_url": "https://app.example/callback?state=abc&code=xyz",
  "code": "authorization-code",
  "state": "session-state",
  "error": ""
}
```

At least `redirect_url` or explicit `code`/`error` plus mandatory `state` is needed. `provider` can be inferred from stored session but must match it if present.

Responses: `{"status":"ok"}`, detailed errors with codes like `unknown or expired state`, `invalid state`, `oauth flow is already completed`, or conflict states.

GET form reads same fields directly from query strings but lacks redirect URL unpacking convenience.

### Cancel Session

```http
DELETE /v0/management/oauth-session?state=<opaque-state>
```

Missing/invalid `state` → `400`. Always returns status `ok`, including whether cancel happened:

```json
{
  "status": "ok",
  "cancelled": true
}
```
