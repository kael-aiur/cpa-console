# Installed Plugins and Plugin Store

Base prefix: `/v0/management`

Plugins can provide extra provider integrations, OAuth providers, menus, and their own Management routes. Console UI should reflect dynamic route registrations where feasible.

## List Installed / Discovered Plugins

```http
GET /v0/management/plugins
```

Response wrapper:

```json
{
  "plugins_enabled": true,
  "plugins_dir": "/absolute/plugins-dir",
  "plugins": [
    {
      "id": "sample-plugin",
      "path": "/absolute/plugins/sample-plugin",
      "configured": true,
      "registered": true,
      "enabled": true,
      "effective_enabled": true,
      "supports_oauth": false,
      "oauth_provider": "",
      "logo": "",
      "config_fields": [],
      "menus": [],
      "metadata": null
    }
  ]
}
```

Field highlights:

- `configured`: an entry exists in YAML `plugins.configs.<id>`.
- `registered`: plugin file was successfully loaded by host.
- `enabled`: per-instance enabled setting.
- `effective_enabled`: global plugins + instance + registered all true.
- `config_fields`: declarative schema for management UI rendering.
- `menus`: dynamic menu entries exposed via legacy resource/plugin endpoints.
- HTML-sensitive strings are sanitized by CPA before emission.

Unknown IDs produce detailed `plugin_not_found`; corrupted directories can yield `plugin_directory_invalid` or `plugin_discovery_failed`.

## Plugin Instance Config

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/plugins/{id}/config` | Returns preserved config object as JSON object |
| `PUT` | `/plugins/{id}/config` | Replaces entire JSON config for plugin instance |
| `PATCH` | `/plugins/{id}/config` | Shallow merges JSON fields into config |

For `PATCH`, a JSON `null` value deletes that top-level config key. Config YAML preserving behavior enables accurate reload after save.

Responses for PUT/PATCH commonly return `{"status":"ok"}` unless validation fails with `invalid_body` or `invalid_config`.

## Enable / Disable One Plugin

```http
PATCH /v0/management/plugins/{id}/enabled
Content-Type: application/json

{
  "enabled": true
}
```

Only sets `plugins.configs.<id>.enabled`; does not alter global `plugins.enabled`. Returns status ok and triggers async configuration refresh.

## Delete Plugin

```http
DELETE /v0/management/plugins/{id}
```

Deletes local binary/file if present and removes its saved instance config. Typical success:

```json
{
  "status": "deleted",
  "id": "sample-plugin",
  "path": "/plugins/sample-plugin",
  "file_deleted": true,
  "configured_removed": true,
  "restart_required": false
}
```

A loaded plugin may require restart, resulting conflict:

```json
{
  "error": "plugin_delete_requires_restart",
  "message": "loaded plugin cannot be deleted while the server is running",
  "restart_required": true
}
```

Partial save failures can still report both delete state and config error details.

## Plugin Store

### Browse Store

```http
GET /v0/management/plugin-store
```

Fetches aggregated plugin catalog across configured store sources and merges locally installed status/update state.

Top-level response includes:

| Field | Description |
| --- | --- |
| `plugins_enabled` | Global plugin system switch |
| `plugins_dir` | Absolute plugin storage directory |
| `sources` | Each configured store source metadata |
| `source_errors` | Per-source fetch failures if any |
| `plugins[]` | Catalog entries enriched with local install/status flags |

Each plugin entry combines registry-provided and local runtime details:

- Registry base info: `store_id`, `source_id`, `source_name`, `source_url`, `id`, `name`, `description`, `author`, `version`, `repository`, `install_type`, `platforms`, `logo`, `homepage`, `license`, `tags`
- Authentication needs: `auth_required`, `auth_configured`
- Local lifecycle: `installed`, `installed_version`, `installed_source_id`, `install_source_status`, `path`, `configured`, `registered`, `enabled`, `effective_enabled`, `update_available`

Source total failure returns `502` style `plugin_store_registry_failed`.

### Install / Update Plugin

```http
POST /v0/management/plugin-store/{id}/install?version=<optional>&source=<optional-source-id>
```

Selection picks matching plugin; ambiguous duplicates may require explicit `source`. Version chooses release variant when available; otherwise default latest/declared version is used.

Typical success response:

```json
{
  "status": "installed",
  "source_id": "official",
  "source_name": "Official Plugin Source",
  "source_url": "...",
  "id": "sample-plugin",
  "version": "1.2.3",
  "install_type": "github-release",
  "path": "/plugins/sample-plugin",
  "plugins_enabled": true,
  "restart_required": false
}
```

Loaded plugin overwrite may return `409` requiring restart. Network or manifest issues return gateway-level errors with descriptive codes such as `plugin_install_failed` or `plugin_manifest_invalid`.

## Dynamic Plugin Routes

CPA registers plugin-declared routes after startup and refreshes them on plugin changes.

Constraints:

- Management routes must use exact paths beneath `/v0/management/`.
- Browser-navigable resource routes are generally exposed below `/v0/resource/plugins/{plugin-id}/`.
- Legacy GET resources carrying a Menu label map to resource endpoints rather than authenticated management-only paths.

Console should refresh navigation/routing state whenever installed plugins change.
