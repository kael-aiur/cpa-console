# Client API Keys and Provider Credential Lists

Base prefix: `/v0/management`

These endpoints mutate `config.yaml` and trigger asynchronous hot reload. Field key spellings intentionally mirror the YAML keys (kebab-case).

## Client Access Keys

Path: `/api-keys`

| Method | Behavior |
| --- | --- |
| `GET` | `{"api-keys":["key-a","key-b"]}` |
| `PUT` | Replace list. Body accepts either `["k"]` or `{"items":["k"]}`. |
| `PATCH` | Update/add item. |
| `DELETE` | Delete one item via query parameters. |

### PATCH /api-keys

JSON fields:

```json
{
  "old": "old-key",
  "new": "replacement",
  "index": 0,
  "value": "replacement"
}
```

Rules:

- If valid `index` + `value` are present, replace that index.
- Otherwise if `old` + `new` are present, replace first matching item.
- If no matching `old` exists, append `new`.
- Other combinations return `400 {"error":"missing fields"}`.

### DELETE /api-keys

Query parameters:

- `index=<integer>`
- or `value=<exact client API key>`

Missing/invalid identifiers return `400`.

## General Provider List Contract

Most provider credential endpoints use the following conventions.

Reads wrap the provider array under its kebab-case field name. For example:

```json
{
  "gemini-api-key": [
    {
      "api-key": "...",
      "base-url": "...",
      "weight": 10
    }
  ]
}
```

Replacement writes accept either a raw array or `{"items":[...]}`.

Patch bodies generally select exactly one existing entry then apply a partial object:

```json
{
  "index": 0,
  "match": "<existing api-key or name>",
  "value": {
    "api-key": "...",
    "weight": 20,
    "disabled": false,
    "proxy-url": ""
  }
}
```

Selection behavior varies slightly by endpoint. `match` may be `api-key`, `name`, provider-specific, or ignored depending on schema. Multiple matches usually require disambiguation by `index` or `base-url`.

Deletions normally select entries through query parameters such as:

- `api-key`
- `base-url`
- `name`
- `index`

If several credentials share the same key, responses demand `base-url` or `index` rather than guessing. Successful mutations typically respond `{"status":"ok"}` and save/reload configuration.

## Supported Provider Lists

The source implements these group operations:

| Path | Read field / main identity | Notes |
| --- | --- | --- |
| `/gemini-api-key` | `gemini-api-key` / `api-key` + optional base URL | Supports per-entry headers, excluded models, retry override, scoped errors, cooling control, weight/priority/prefix/base URL/proxy URL |
| `/interactions-api-key` | `interactions-api-key` / same structural schema as Gemini keys | Google Interactions native provider list |
| `/claude-api-key` | `claude-api-key` / `api-key` + optional base URL | Includes model routing, cloak/fingerprint/CCH-related advanced fields |
| `/codex-api-key` | `codex-api-key` / `api-key` + optional base URL | Includes WebSocket, Alpha Search, model aliases, retry overrides |
| `/xai-api-key` | `xai-api-key` / same structure as Codex keys (`type XAIKey = CodexKey`) | xAI compatible providers |
| `/openai-compatibility` | `openai-compatibility` / `name` | Replaces whole external OpenAI-compatible provider entries |
| `/vertex-api-key` | `vertex-api-key` / `name` or index | Vertex-compatible third-party providers; replacement requires each entry's `api-key` |

Each path exposes `GET`, full-list `PUT`, single-item `PATCH`, and single-item `DELETE` unless noted above.

## OAuth Global Model Controls

Model exclusion and alias maps also follow the standard list/group behavior.

### `/oauth-excluded-models`

Shape:

```json
{
  "oauth-excluded-models": {
    "claude": ["model-a*"],
    "codex": ["model-b"]
  }
}
```

`PATCH` selects one `provider`; required `value` carries updated excluded models for it.

### `/oauth-model-alias`

Shape uses channel keys supported by CPA:

```json
{
  "oauth-model-alias": {
    "claude": [
      {
        "name": "claude-sonnet-5",
        "alias": "fast-claude"
      }
    ]
  }
}
```

Supported channels stated in config types include: `vertex`, `aistudio`, `antigravity`, `claude`, `codex`, `kimi`, `xai`.

`PATCH` requires `channel` plus updated aliases.

### `/oauth-request-scoped-errors`

Configures custom classification of upstream errors for OAuth/file-backed credentials.

Rule object:

| Field | Meaning |
| --- | --- |
| `status` | Optional HTTP status code |
| `match` | Substrings matched in error body |
| `match-regexr` | Regular expressions matched in body |
| `action` | One of `stop`, `stop-and-cooldown`, `continue`, `continue-and-cooldown` |

Map values are grouped by channel/provider. `PATCH` requires `channel` plus updated rule list.
