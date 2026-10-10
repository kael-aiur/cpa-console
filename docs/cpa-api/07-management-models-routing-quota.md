# Models, Routing, Quota and Usage

Base prefix: `/v0/management`

## Routing Strategy

See [04-management-core.md](./04-management-core.md#routing-strategy).

Supported normalized values:

| Request aliases | Canonical value |
| --- | --- |
| empty, `round-robin`, `roundrobin`, `rr` | `round-robin` |
| `weighted-round-robin`, `weightedroundrobin`, `wrr` | `weighted-round-robin` |
| `fill-first`, `fillfirst`, `ff` | `fill-first` |

The GET returns the canonical value when known, otherwise the raw stored strategy.

Routing also contains advanced settings such as session affinity in full configuration (`GET /config`), but they currently have no dedicated scalar Management endpoint.

## Quota-Automated Switches

These boolean-like switch endpoints persist under `quota-exceeded`.

### Switch Project

| Methods | Path |
| --- | --- |
| `GET` | `/quota-exceeded/switch-project` |
| `PUT` / `PATCH` | `/quota-exceeded/switch-project` |

Read:

```json
{
  "switch-project": false
}
```

Write body is the generic scalar form:

```json
{"value": true}
```

### Switch Preview Model

| Methods | Path |
| --- | --- |
| `GET` | `/quota-exceeded/switch-preview-model` |
| `PUT` / `PATCH` | `/quota-exceeded/switch-preview-model` |

Read field: `"switch-preview-model"`. Write uses same `{"value":...}` contract.

## Reset Routing Quota / Cooldown

```http
POST /v0/management/reset-quota
Content-Type: application/json

{
  "auth_index": "<value-from-auth-files>"
}
```

**This only clears CPA routing quota/cooldown observations on one credential; it does not restore the provider account’s real quota.**

The corresponding v8 route is `POST /v8/management/routing/cooldown/reset`.

Success response:

```json
{
  "status": "ok",
  "auth_index": "<auth-index>",
  "models": []
}
```

Failures include invalid body/auth-index missing (`400`) and auth not found (`404`).

## Reset Provider Account Quota

```http
POST /v0/management/quota/reset
Content-Type: application/json

{"auth_index": "<value-from-auth-files>"}
```

Optional body fields: `provider`, `plugin_id`. By default CPA resolves the provider from the credential.
This invokes a registered plugin/provider quota-reset implementation. A credential being an auth file does not
by itself imply reset support. Missing support/plugin host yields `501`; provider rejection/failure yields `502`;
unknown credential yields `404`. A successful response has `status: "ok"` and `auth_index`, with an optional message.

In the reviewed implementation, provider success is followed by CPA's core routing reset. If that internal
reset fails, CPA returns `500` even though the provider reset may already have completed. Treat such responses
as uncertain rather than blindly retrying a potentially quota-credit-consuming operation.

The console's admin reset is restricted to Codex OAuth auth files (`openai` is normalized to `codex`).
It explicitly calls this endpoint first and `/reset-quota` second, stopping if the first step fails.
Both calls must succeed for a complete success. No automatic reset retry is performed.

## API Key Usage Aggregation

```http
GET /v0/management/api-key-usage
```

Reports aggregate success/failure plus recent bucket data for all API-key credentials tracked by the core auth manager.

Response structure groups first by provider key (often lower-cased provider or compat name), then by composite credential identity.

Example shape:

```json
{
  "claude": {
    "https://api.anthropic.com|sk-key": {
      "success": 120,
      "failed": 2,
      "recent_requests": []
    }
  }
}
```

Recent request buckets are snapshots from runtime auth state and are merged across duplicate/related records.

## Recent Usage Queue

```http
GET /v0/management/usage-queue?count=10
```

- Pops queued usage events. Repeated calls consume items.
- `count`: optional positive integer; defaults to `1`.
- Invalid count yields `400`.

Response is a JSON array of raw usage record objects; source preserves valid embedded JSON without re-marshalling wrappers.

In-memory retention duration is controlled globally by `redis-usage-queue-retention-seconds` visible through full config.
