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

## Reset Quota

```http
POST /v0/management/reset-quota
Content-Type: application/json

{
  "auth_index": "<value-from-auth-files>"
}
```

This resets quota/cooldown observations on one credential.

Success response:

```json
{
  "status": "ok",
  "auth_index": "<auth-index>",
  "models": []
}
```

Failures include invalid body/auth-index missing (`400`) and auth not found (`404`).

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
