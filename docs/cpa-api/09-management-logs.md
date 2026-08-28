# Runtime Logs, Request Logs and Usage Queue

Base prefix: `/v0/management`

Most log functionality depends on `logging-to-file=true`; otherwise calls reject with messages like `logging to file disabled`.

## Runtime Logs

```http
GET /v0/management/logs?limit=100&after=1770000000&cursor=<opaque>
```

Query parameters:

| Parameter | Meaning |
| --- | --- |
| `limit` | Optional positive integer number of lines. Zero means server-appropriate default/all filtered result. |
| `after` | Unix second cutoff; older lines excluded. |
| `cursor` | Opaque continuation token returned previously. Takes precedence over legacy timestamp paging semantics. |

Cursor reset behavior occurs automatically when prior log files changed too much; payload then adds:

```json
{ "cursor-reset": true }
```

Successful response always includes these keys:

```json
{
  "lines": ["2026-08-27 12:00:00 ...", "..."],
  "line-count": 2,
  "latest-timestamp": 1785220800,
  "next-cursor": "<opaque>"
}
```

Errors may indicate disabled logging, unconfigured directory, invalid limit, or filesystem read failure.

## Clear Runtime Log Files

```http
DELETE /v0/management/logs
```

Removes rotated log files and truncates active log file.

Response shape:

```json
{
  "success": true,
  "message": "Logs cleared successfully",
  "removed": 3
}
```

## Error Request Logs

When detailed request logging is disabled, standalone files beginning `error-` and ending `.log` expose upstream/request failures.

```http
GET /v0/management/request-error-logs
```

Response:

```json
{
  "files": [
    {
      "name": "error-2026-08-27T120000Z-requestid.log",
      "size": 4096,
      "modified": 1785220800
    }
  ]
}
```

If request logging is enabled, this endpoint intentionally returns `{ "files": [] }`.

## Download Error Request Log

```http
GET /v0/management/request-error-logs/{name}
```

Returns file attachment. Name must begin with `error-`, end `.log`, and contain no path separators; violations/unknown names yield appropriate `400`/`404`.

## Locate Any Request Log by ID

Log filenames conventionally end `-{requestID}.log`.

```http
GET /v0/management/request-log-by-id/{requestID}
GET /v0/management/request-log-by-id?id=<requestID>
```

Returns matching file attachment independent of whether it began specifically with `error-`. Missing identifier returns `400`; unmatched ID returns `404`.

## Detailed Request Logging Switch

Endpoints exist because request logging toggle changes error-log discovery semantics.

| Methods | Path | Type |
| --- | --- | --- |
| `GET` | `/request-log` | Read boolean |
| `PUT` / `PATCH` | `/request-log` | Set boolean |

Detailed behavior is included in [04-management-core.md](./04-management-core.md).
