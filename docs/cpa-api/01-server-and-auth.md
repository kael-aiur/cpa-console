# Server, Authentication and Conventions

## Deployment Defaults

The upstream service is a Gin HTTP server. Common settings are loaded from `config.yaml`.

```yaml
host: ""
port: 8317
auth-dir: "~/.cli-proxy-api"
api-keys:
  - "<client-api-key>"
remote-management:
  allow-remote: false
  secret-key: "<bcrypt-or-plaintext-hashed-on-start>"
```

Notes:

- `port` defaults to `8317`.
- Empty `host` binds all interfaces.
- The console should expose these values in its connection profile rather than hard-code them.

## CORS

All responses include permissive browser CORS headers:

- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS`
- `Access-Control-Allow-Headers: *`

`OPTIONS` returns `204`.

Source: `internal/api/server_middleware.go`.

Management responses also expose build/plugin capability headers:

- `X-CPA-VERSION`
- `X-CPA-COMMIT`
- `X-CPA-BUILD-DATE`
- `X-CPA-SUPPORT-PLUGIN`

## Client-Facing Inference Authentication

Routes under `/v1`, `/openai/v1`, `/backend-api/codex`, and `/v1beta` require one of the configured `api-keys` unless no access manager/providers are configured.

Accepted credentials are extracted from:

- `Authorization: Bearer <key>`
- `X-Goog-Api-Key: <key>`
- `X-Api-Key: <key>`
- Query parameters `key=<key>` or `auth_token=<key>`

Source: `internal/access/config_access/provider.go`.

Error responses generally follow the target protocol. Standard Realtime errors wrap fields such as:

```json
{
  "error": {
    "message": "...",
    "type": "authentication_error",
    "param": null,
    "code": "invalid_api_key"
  }
}
```

## Management Authentication

All `/v0/management/**` routes require both availability and a management key.

Availability rules:

1. Routes exist only after a secret key becomes available.
2. Home mode disables Management API with `404`.
3. A valid secret key is always required, including localhost requests.

Accepted authorization headers:

```http
Authorization: Bearer <management-key>
X-Management-Key: <management-key>
```

Additional rules:

- If `remote-management.allow-remote` is false, only loopback clients may call Management API.
- Five failed authentications cause approximately a 30-minute IP ban.
- The configured plaintext `remote-management.secret-key` is hashed on startup.
- Environment variable `MANAGEMENT_PASSWORD`, if present, is accepted and enables remote override.
- Without any configured secret, all Management routes return `404`.

## Error Conventions

Most Management JSON failures return:

```json
{
  "error": "machine-readable-or-short-message"
}
```

Richer handler-specific errors may add fields such as `message`, `path`, or `restart_required`. Successful mutation endpoints commonly return:

```json
{
  "status": "ok"
}
```

Configuration mutations persist to the YAML file and asynchronously hot-reload runtime state.
