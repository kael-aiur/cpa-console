# Management Core Configuration and Tools

Base prefix: `/v0/management`

Management authentication is described in [01-server-and-auth.md](./01-server-and-auth.md).

`PATCH` aliases to the corresponding `PUT` implementation for scalar settings unless noted otherwise.

## Full Configuration

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/config` | Returns runtime `Config` as JSON. Server-only fields such as `host`, `port`, `auth-dir`, and `remote-management` are JSON-hidden. |
| `GET` | `/config.yaml` | Returns raw YAML with comments preserved; response content type is `application/yaml`. |
| `PUT` | `/config.yaml` | Replaces complete YAML configuration after validation, saves it, reloads in-memory config, returns `{"ok":true,"changed":["config"]}` |

### PUT /config.yaml

Request body: full YAML document.

Success:

```json
{
  "ok": true,
  "changed": ["config"]
}
```

Failure examples:

- `400 invalid_yaml`
- `422 invalid_config`
- `404 not_found` on GET when file is absent
- `500 write_failed`

## Latest Upstream Version

```http
GET /v0/management/latest-version
```

Fetches GitHub's latest release metadata for CLIProxyAPI through the configured global proxy.

Response:

```json
{
  "latest-version": "v1.2.3"
}
```

Failures use fields like `request_create_failed`, `unexpected_status`, or `decode_failed`.

## Generic Scalar Settings

The table lists only the distinctive field names. `GET` wraps values under that field, while `PUT`/`PATCH` require `{"value": <new-value>}`. Every mutation persists and hot-reloads configuration.

| Methods | Path | Type / Values | Success read shape |
| --- | --- | --- | --- |
| `GET`, `PUT`, `PATCH` | `/debug` | boolean | `{"debug":true}` |
| `GET`, `PUT`, `PATCH` | `/logging-to-file` | boolean | `{"logging-to-file":true}` |
| `GET`, `PUT`, `PATCH` | `/logs-max-total-size-mb` | integer ≥ 0, negative normalized to zero | `{"logs-max-total-size-mb":1024}` |
| `GET`, `PUT`, `PATCH` | `/error-logs-max-files` | integer, negative normalized to `10` | `{"error-logs-max-files":10}` |
| `GET`, `PUT`, `PATCH` | `/usage-statistics-enabled` | boolean | `{"usage-statistics-enabled":true}` |
| `GET`, `DELETE` | `/proxy-url` | URL string or empty string | `{"proxy-url":"socks5://..."}` |
| `GET`, `PUT`, `PATCH` | `/request-log` | boolean | `{"request-log":false}` |
| `GET`, `PUT`, `PATCH` | `/ws-auth` | boolean | `{"ws-auth":false}` |
| `GET`, `PUT`, `PATCH` | `/request-retry` | integer | `{"request-retry":1}` |
| `GET`, `PUT`, `PATCH` | `/max-retry-credentials` | integer, `<=0` means try all | `{"max-retry-credentials":3}` |
| `GET`, `PUT`, `PATCH` | `/max-retry-interval` | integer seconds | `{"max-retry-interval":30}` |
| `GET`, `PUT`, `PATCH` | `/force-model-prefix` | boolean | `{"force-model-prefix":false}` |
| `GET`, `PUT`, `PATCH` | `/routing/strategy` | `round-robin`, aliases `roundrobin`/`rr`; `weighted-round-robin`, alias `wrr`; `fill-first`, alias `ff` | `{"strategy":"weighted-round-robin"}` |

Invalid scalar body returns `400 {"error":"invalid body"}`; invalid routing strategy also returns `400`.

Example:

```http
PUT /v0/management/routing/strategy
Content-Type: application/json

{"value": "wrr"}
```

## Generic HTTP Request Tool

```http
POST /v0/management/api-call
```

Runs an arbitrary outbound HTTP request from CPA using a selected credential and proxy rules. This is useful for console-side provider diagnostics.

Request:

```json
{
  "auth_index": "<auth-index>",
  "method": "POST",
  "url": "https://api.example.com/v1/models",
  "proxy_url": "",
  "header": {
    "Authorization": "Bearer $TOKEN$",
    "Content-Type": "application/json"
  },
  "data": "{}"
}
```

Fields:

- `auth_index` may be provided as `auth_index`, `authIndex`, or `AuthIndex`; optional but needed for `$TOKEN$` substitution.
- `method`: required.
- `url`: required absolute HTTP(S) URL.
- `proxy_url`: optional HTTP, HTTPS, SOCKS5, SOCKS5H, `direct`, or `none`.
- `header`: optional map. Magic value `$TOKEN$` is replaced by credential access/API token.
- `data`: raw request-body string.

Proxy priority: explicit `proxy_url`, selected credential proxy, global proxy, then direct connection. Environment proxies are not used here.

Success response:

```json
{
  "status_code": 200,
  "header": { "Content-Type": ["application/json"] },
  "body": "{...}"
}
```

Common failures include `missing method`, `invalid url`, `invalid proxy_url`, `auth token not found`, and upstream `request failed`.
