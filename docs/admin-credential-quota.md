# 管理员凭证额度查询与重置

## 接口与权限

- `GET /admin/credentials` 的每个凭证包含归一化的 `provider`（`openai` → `codex`）。标签修改返回同样结构。
- `GET /admin/credentials/{id}/quota`：返回 `{ "quota": ... }`，复用用户后台 QuotaManager 和额度结构。
- `POST /admin/credentials/{id}/reset-quota`：无需业务请求体，要求管理员登录和有效 CSRF。
- `{id}` 是 console 数据库 ID，由后端解析 CPA `auth_index`。不存在返回 404；非 Codex OAuth 凭证返回 400。
- `/admin/**` 统一由现有 Spring Security 管理员权限规则保护，前端按钮不作为安全边界。

## 重置结果

成功返回 HTTP 200：

```json
{"status":"ok","quota_reset":"completed","cooldown_reset":"completed","message":"额度和账号冷却已重置"}
```

上游操作失败返回 HTTP 502，携带相同阶段字段：

- `status`：`error`、`partial_success`。
- `quota_reset`：`completed`、`failed`、`unknown`。
- `cooldown_reset`：`completed`、`failed`、`unknown`、`not_attempted`。
- `message`：可展示的提示，不包含上游原始账号/认证信息。

流程为供应商额度重置 → CPA 冷却重置。额度阶段失败后不继续清冷却。
网络中断、无有效成功响应及上游非明确拒绝的 5xx（例如 500）的额度结果视为不确定，不自动重试。
部分成功不能回滚。前端收到部分成功或不确定结果后禁用本页该凭证重置按钮，提示先核实状态，避免误消耗额度重置次数。

## 前端行为

列表先显示，再最多同时查询 10 个凭证额度。每行独立加载、报错和重试查询。
每个凭证的操作区均有刷新图标按钮（循环箭头，悬停提示“刷新额度”），仅重新查询当前凭证额度；查询期间显示“刷新中…”并禁用按钮，重置期间也禁止手动刷新。桌面表格和移动端卡片均支持。
操作区采用纯图标按钮：铅笔表示修改标签，循环箭头表示刷新，回转箭头与小钟表示重置额度及冷却；保留悬停提示、无障碍标签和重置确认框，执行期间图标旋转（减少动态效果模式下关闭动画）。
仅 `auth_file` 且 `provider === "codex"` 显示重置按钮，点击需确认。
重置成功后自动查询目标凭证额度一次；查询失败单独提示，不重新执行重置。
部分成功/不确定结果也查询额度以帮助核实，不自动重复重置。
桌面额度列与移动端卡片使用共享额度组件；用户额度页复用同一组件。
未新增额度持久化或数据库结构变更。

## 验证

- `mvn test`：包括 CPA 模拟 HTTP 合同、管理员业务流程、真实 SecurityConfig 下的权限和 CSRF 测试。
- `npm run build:dev` / `npm run build:spring`：Vue/TypeScript 类型检查和构建。
- `mvn package -DskipTests`：完整打包。
- 真实额度重置可能消耗供应商重置次数；自动化测试只使用模拟，不操作真实账号。
