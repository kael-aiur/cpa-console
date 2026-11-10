# 定时凭证监控 API（T10/T11）

监控功能只读既有监控证据，所有查询路径 GET，绝不触发 verify POST。响应字段统一 snake_case；错误返回
`{"code": "<稳定本地码>", "message": "<固定本地文案>"}`，不回显请求参数、端点、真实身份或上游原文。

## 管理端点（/admin/monitor，需登录 + role=admin + CSRF）

全局 SecurityConfig 已对 `/admin/**` 强制登录且 `role=admin`，POST/PATCH 不豁免 CSRF；控制器层不重复实现权限。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/monitor/credentials` | 全量实时候选列表；`candidate_id` 为不透明短期令牌，`sequence` 为本次列表内序号，`display_name` 为 ≤64 字的安全可区分名称（优先受限上游 label，缺名/同名回退 `provider #N`），`model_choices` 仅 supported 候选提供；unsupported 候选列出但不可选。列表是短期快照，替换或创建消耗前一直有效，不按时间误判过期 |
| GET | `/admin/monitor/credentials/{candidate_id}/models` | 单个仍有效候选的模型列表与 `model_choices`；候选过期返回 404 `CANDIDATE_NOT_FOUND` |
| GET | `/admin/monitor/tasks` | 全量任务列表（含停用/阻塞任务） |
| POST | `/admin/monitor/tasks` | 创建任务；`enabled` 只接受 `false` 或省略（默认停用）；body 仅允许 `safe_name/cron_expression/zone_id/instance_key/model_choice_id` |
| PATCH | `/admin/monitor/tasks/{task_id}` | 编辑任务；`expected_revision` 必填；body 仅允许 `expected_revision/safe_name/cron_expression/zone_id/enabled`；冲突返回 409 `REVISION_CONFLICT` 不覆盖 |
| GET | `/admin/monitor/tasks/{task_id}/runs` | 管理历史；支持 `start/end/view/cursor/limit`；`safe_admin_summary` 仅为本地模板（≤512），`cpa_http_status` 仅为可得整数 |

创建请求里的 `instance_key` 指候选列表返回的不透明 `candidate_id`（不可变目标在后端固定，真实 id/index/模型不回传）。任务/历史的 `safe_model_display` 由创建时的安全显示名 + 模型组成（缺名回退 `provider #N`），不再回显真实身份。

## 登录用户端点（/api/monitor，需登录，只读）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/monitor/tasks` | 全部任务卡片（含停用任务）；窗口默认 as_of−24h，或成对 `start/end`（ISO-8601 绝对时间，start<end） |
| GET | `/api/monitor/tasks/{task_id}/runs` | 单任务历史；`view=verification|events`，`cursor` 为签名游标，`limit` 默认 20 最大 100 |

窗口、`as_of`、`view`、cursor 必须整页一致；数据变化后旧 cursor 返回 409 `SNAPSHOT_CHANGED`，客户端应回第一页刷新，不得混页。
仅返回白名单字段：安全名/模型、结果与调度分离的最近记录、窗口计数/ratio/coverage/revision；无真实 id/index/provider 账号/主机/管理 cron 细节/内部诊断/原始 credential；UNKNOWN 不显示为失效，保留例外不暗示实时健康。

## 错误码

| code | HTTP | 语义 |
| --- | --- | --- |
| `BAD_REQUEST` / `MISSING_FIELD` / `UNKNOWN_FIELD` / `EMPTY_BODY` / `EXPECTED_REVISION_REQUIRED` / `INVALID_WINDOW` / `INVALID_CURSOR` / `IMMUTABLE_FIELD` / `PLAN_INVALID` | 400 | 本地校验失败，不改状态 |
| `TASK_NOT_FOUND` / `CANDIDATE_NOT_FOUND` / `MODEL_CHOICE_NOT_FOUND` | 404 | 目标不存在（候选过期不猜测） |
| `REVISION_CONFLICT` / `CANDIDATE_EXPIRED` / `SNAPSHOT_CHANGED` / `IDENTITY_INVALID` | 409 | 并发修订或快照变化，需刷新后重试，不覆盖 |
| `STORAGE_UNAVAILABLE` / `CPA_UNAVAILABLE` | 503 | 本地存储或 CPA 读取暂不可用 |

状态码全部为控制台本地状态；run 的上游 HTTP 细节仅经 `cpa_http_status`（管理员历史）透出，不与本地状态混淆。
