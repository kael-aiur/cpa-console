# 批次8 T13+T14 开发自检记录

基线：develop @ `733f443825d741f772ae32753b92fd5e00700982`
分支：`batch8-t13-t14`
PR：https://github.com/kael-aiur/cpa-console/pull/29
head：`2a4d9d342a4458f0e4ae3015ba32a1228303e04d`

## 环境

- Node v24.22.0 / npm 11.19.1
- 隔离工作区，`npm ci` 干净安装（76 packages，exit 0）
- 全部自检使用 mock fetch / 合成 fixture；未连接真实后端、真实 CPA 或生产数据

## 真实命令与退出码

| 命令（console-page/） | 退出码 | 说明 |
| --- | --- | --- |
| `npm ci` | 0 | 干净安装 |
| `npm run type-check` | 0 | vue-tsc --build；基线与每次提交后均通过 |
| `npm run build:dev` | 0 | 产物包含 `#/admin-monitor` 与 `#/monitor-records` 路由及两侧菜单文案 |
| `tsc -p .tsconfig.selftest.json`（本地临时配置，已 gitignore） | 0 | 编译离线自检脚本 |
| `node .selftest-out/src/services/__tests__/monitorApi.selftest.js` | 0 | T12 适配层 18 次请求断言（CSRF/cookie/仅白名单接口/无 execute/delete/409 保留窗口/null ratio） |
| `node .selftest-out/src/pages/__tests__/monitorPages.selftest.js` | 0 | T13/T14 页面逻辑断言（见下） |

## 页面逻辑断言摘要

### T13 AdminMonitorPage

- 候选过期：`formatAdminCandidateExpiry(null|0|≤now)` → “候选已过期，请重新获取候选列表”；组件内候选列表刷新后清理过期选择，404/409 `CANDIDATE_*` 触发重新拉取，不按名称恢复。
- 创建默认停用：payload 仅含 `safe_name/cron_expression/zone_id/instance_key/model_choice_id`，无 `enabled` 字段（T12 自检第4条断言）；UI 提交按钮文案“创建（默认停用）”。
- 计划帮助：`describeAdminPlan` 输出六段 cron 说明 + UTC 绝对时间 + 所选时区；本地仅校验六段形状，复杂算法交给后端，提交前不显示“已成功保存”。
- 编辑不可变：编辑抽屉目标/模型只读展示；PATCH payload 仅 `expected_revision/safe_name/cron_expression/zone_id/enabled`（T12 自检第5条断言）；409 `REVISION_CONFLICT` → 刷新最新版本 + 明确提示重试。
- 阻塞/未发/已发/停用文案：`CPA_TARGET_REJECTED` → “目标拒绝：请检查凭证与模型后重新启用”；`TZDB_CERTIFICATE_INVALID` → 证书失效文案；`NOT_SENT` → “未发送”；`MAY_HAVE_SENT` → “可能已发送”（不等于成功）；停用行显示“停用后不调度”。
- 历史无原始响应：仅渲染本地模板 `safe_admin_summary`、`reason_code` 与整数 `cpa_http_status`；上次验证与最近调度分列；任务行不显示受限实例地址/id/index（grep 断言无 `{{…instance_key/task_id/candidate_id/run_id}}` 输出）。
- 固定逻辑目标说明：创建/编辑抽屉注明“目标与模型创建后不可修改，如需更换请另建任务；候选过期重新获取，不按名称恢复”。

### T14 MonitorRecordsPage

- 五类状态及执行中文案：SUCCESS=成功 / FAILED=失败 / PENDING=执行中 / SKIPPED=已跳过 / UNKNOWN=结果不确定 / CONFIG_ERROR=配置异常。
- 最近跳过不覆盖旧成功：卡片结论优先取 `latest_verification`；事件仅在 SUCCESS/FAILED/UNKNOWN 之外以“最近调度事件：已跳过”展示。
- 80% 与 null：“—” 一致：`formatMonitorRatio(0.8)=80%`；`ratio=null` → “—”（零样本与完全无保留均不显示 0%）；HALF_UP 两位去尾零。
- 空/保留文案：无任务 → “当前没有监控任务”；零样本完整窗口 → “窗口内暂无有效验证”；部分缺口 → “历史数据不完整，仅基于当前保留记录展示”；保留例外 → “存在保留例外，部分数据已按策略删失”；完全无保留 → “该窗口内无保留数据”。
- 409 SNAPSHOT_CHANGED：卡片页与历史页均显示“监控数据已更新，请刷新”，清空旧 cursor/旧页，保留用户窗口；不循环自动请求（仅手动刷新按钮触发）。
- 仅 GET 断言：T12 自检第15条确认全部读请求为 GET、写请求仅 POST/PATCH 且带 CSRF；页面无任何 DELETE/execute 调用。
- 卡片与同窗口计数一致：卡片跳历史使用同一 start/end/as_of（route query 携带窗口）；history view 从同一 response 派生 revision/as_of/coverage。

### 共同

- 无 `v-html`、`innerHTML`、`document.write`（grep 0 命中）。
- 敏感扫描：新页面/逻辑/fixture 无上游地址、真实账号、credential、真实 id/index 字面量；仅使用合成 token（`candidate-token-a/b`、`task-*`、`run-*`）。
- 样式使用 scoped CSS + 全局响应式变量；320px 断点（480px 内容单列 + 700px 表单纵向）覆盖卡片/抽屉/历史工具栏。
- 键盘焦点：全部交互使用原生 button/input/select；错误提示使用 `role="alert"`/`aria-live`，label 与输入通过 for/id 或包裹关联。

## 未验证项

- 真实浏览器级 320px 截图与键盘焦点交互（本环境无浏览器；页面结构遵循与既有页面一致的响应式/焦点模式）。
- 真实后端联调（G6/G7 未通过，契约不授权）。
- 组件级挂载渲染（vue-tsc 类型检查 + 纯函数逻辑断言覆盖；未引入新测试框架以保持最小变更）。
