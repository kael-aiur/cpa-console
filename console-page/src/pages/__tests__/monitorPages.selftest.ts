/**
 * T13+T14 offline self-test (mock fetch only; no real backend, no real browser). The page
 * components are compiled by vue-tsc during `npm run type-check`; this script verifies the
 * T13/T14 page logic modules directly: candidate expiry handling, plan help, revision
 * conflict flow, state copy, ratio/time formatting, next-fire display, and the whole-page
 * SNAPSHOT_CHANGED refresh contract. Run via the tsc+rewrite flow used by the T12
 * self-test (see monitorApi.selftest.ts header).
 */
import { strict as assert } from 'node:assert'
import {
  buildAdminPlanDraft,
  describeAdminPlan,
  adminTaskStateView,
  resolveAdminBlockedCopy,
  ADMIN_BLOCKED_COPY,
  ADMIN_RUN_SUMMARY_COPY,
  formatAdminRunSummaryLine,
  adminRevisionState,
  ADMIN_EDITABLE_FIELDS,
} from '@/utils/adminMonitorPageLogic'
import {
  MONITOR_STATE_COPY,
  resolveMonitorCardSummary,
  MONITOR_EMPTY_COPY,
  resolveMonitorEmptyCopy,
  monitorHistoryView,
} from '@/utils/monitorRecordsPageLogic'
import { formatMonitorRatio, formatMonitorUtcTime, monitorWindowSummary, formatMonitorAge } from '@/utils/monitorDisplay'

const NOW = 1762500000000
const DAY = 86_400_000

// --- T13: candidate snapshot semantics moved to adminCandidateFlow.selftest.ts --
// (NATI-171-FE-01 rework: expiry is server-declared only; see that standalone self-test.)

// --- T13: create draft defaults (disabled-by-default contract) ----------------
const draft = buildAdminPlanDraft('0 0 3 * * *', 'UTC')
assert.equal(draft.cron_expression, '0 0 3 * * *')
assert.equal(draft.zone_id, 'UTC')
assert.ok(!('enabled' in draft), 'create payload must never carry enabled=true')

// --- T13: plan help text ------------------------------------------------------
const plan = describeAdminPlan('0 0 3 * * *', 'UTC', NOW + 3 * 60 * 60 * 1000)
assert.ok(plan.includes('10:20:00'), 'next fire help must carry the UTC wall time')
assert.ok(plan.includes('UTC'), 'next fire help must state the zone')

// --- T13: run-level copy (blocked / unknown / not-sent / stopped) -------------
assert.equal(ADMIN_BLOCKED_COPY.CPA_TARGET_REJECTED, '目标拒绝：请检查凭证与模型后重新启用')
assert.equal(ADMIN_RUN_SUMMARY_COPY.NOT_SENT, '未发送')
assert.equal(ADMIN_RUN_SUMMARY_COPY.MAY_HAVE_SENT, '可能已发送')
assert.equal(formatAdminRunSummaryLine('NOT_SENT'), '未发送')
assert.equal(formatAdminRunSummaryLine('MAY_HAVE_SENT'), '可能已发送')
assert.equal(resolveAdminBlockedCopy(null), '')
assert.equal(resolveAdminBlockedCopy('CPA_TARGET_REJECTED'), ADMIN_BLOCKED_COPY.CPA_TARGET_REJECTED)
assert.equal(resolveAdminBlockedCopy('TZDB_CERTIFICATE_INVALID'), ADMIN_BLOCKED_COPY.TZDB_CERTIFICATE_INVALID)

// --- T13: task row view ------------------------------------------------------
const enabledView = adminTaskStateView({ enabled: true, blocked_reason: null, next_fire_at: NOW + 60_000 } as never, NOW)
assert.equal(enabledView.stateLabel, '已启用')
assert.ok(enabledView.nextFireText.includes('UTC'))
const blockedView = adminTaskStateView({ enabled: false, blocked_reason: 'CPA_TARGET_REJECTED', next_fire_at: null } as never, NOW)
assert.equal(blockedView.stateLabel, '配置阻塞')
const disabledView = adminTaskStateView({ enabled: false, blocked_reason: null, next_fire_at: null } as never, NOW)
assert.equal(disabledView.stateLabel, '已停用')
assert.equal(adminTaskStateView({ enabled: true, blocked_reason: null, next_fire_at: null } as never, NOW).nextFireText, '待重新校验')

// --- T13: revision conflict --------------------------------------------------
const conflict = adminRevisionState({ code: 'REVISION_CONFLICT' })
assert.equal(conflict, '任务已被他人修改，已刷新最新版本，请重试')
assert.ok(ADMIN_EDITABLE_FIELDS.every((field) => ['safe_name', 'cron_expression', 'zone_id', 'enabled'].includes(field)))

// --- T14: state copy ---------------------------------------------------------
assert.equal(MONITOR_STATE_COPY.SUCCESS, '成功')
assert.equal(MONITOR_STATE_COPY.FAILED, '失败')
assert.equal(MONITOR_STATE_COPY.PENDING, '执行中')
assert.equal(MONITOR_STATE_COPY.SKIPPED, '已跳过')
assert.equal(MONITOR_STATE_COPY.UNKNOWN, '结果不确定')
assert.equal(MONITOR_STATE_COPY.CONFIG_ERROR, '配置异常')

// --- T14: card summary (five conclusions, skip-not-overwrite, coverage) -------
const successCard = resolveMonitorCardSummary({
  verification: { success_count: 8, failed_count: 1, unknown_count: 1, sample_count: 10, ratio: 0.8 },
  latest_verification: { state: 'SUCCESS', started_at: NOW - DAY, finished_at: NOW - DAY + 4_000 },
  latest_event: { state: 'SKIPPED', started_at: NOW - 3_600_000, finished_at: NOW - 3_600_000 + 1_000 },
  coverage: {
    complete: true,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW - 6 * DAY,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [{ start: NOW - DAY, end: NOW }],
    observation_gaps: [],
  },
  enabled: true,
  blocked_reason: null,
}, NOW)
assert.equal(successCard.conclusion, '最近一次验证成功')
assert.ok(successCard.conclusionDetail.includes('已跳过'), 'latest skipped event must not overwrite the older success verdict')
assert.equal(successCard.ratioText, '80%')
assert.ok(successCard.coverageNote === '' , 'complete coverage adds no extra note')

const unknownCard = resolveMonitorCardSummary({
  verification: { success_count: 0, failed_count: 0, unknown_count: 2, sample_count: 2, ratio: 0 },
  latest_verification: { state: 'UNKNOWN', started_at: NOW - DAY, finished_at: NOW - DAY },
  latest_event: null,
  coverage: {
    complete: true,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW - 6 * DAY,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [{ start: NOW - DAY, end: NOW }],
    observation_gaps: [],
  },
  enabled: true,
  blocked_reason: null,
}, NOW)
assert.equal(unknownCard.conclusion, '最近验证结果不确定')
assert.equal(unknownCard.ratioText, '0%')

const noDataCard = resolveMonitorCardSummary({
  verification: { success_count: 0, failed_count: 0, unknown_count: 0, sample_count: 0, ratio: null },
  latest_verification: null,
  latest_event: null,
  coverage: {
    complete: true,
    retained_sample_intervals: [],
    observation_gaps: [],
    loss_intervals: [],
    collection_started_at: NOW - 6 * DAY,
  } as never,
  enabled: true,
  blocked_reason: null,
}, NOW)
assert.equal(noDataCard.conclusion, '窗口内暂无有效验证')
assert.equal(noDataCard.ratioText, '—', 'null ratio must render as an em dash, never 0%')

const partialCard = resolveMonitorCardSummary({
  verification: { success_count: 2, failed_count: 1, unknown_count: 0, sample_count: 3, ratio: 0.6666666666666666 },
  latest_verification: { state: 'FAILED', started_at: NOW - 5 * 3_600_000, finished_at: NOW - 5 * 3_600_000 + 2_000 },
  latest_event: null,
  coverage: {
    complete: false,
    retained_sample_intervals: [{ start: NOW - 2 * DAY, end: NOW }],
    observation_gaps: [{ start: NOW - 6 * DAY, end: NOW - 5 * DAY }],
    loss_intervals: [],
    collection_started_at: NOW - 6 * DAY,
  } as never,
  enabled: true,
  blocked_reason: null,
}, NOW)
assert.equal(partialCard.conclusion, '最近一次验证失败')
assert.equal(partialCard.ratioText, '66.67%')
assert.ok(partialCard.coverageNote.includes('数据不完整'), 'coverage gaps must stay visible, not hidden')

const noRetentionCard = resolveMonitorCardSummary({
  verification: { success_count: 0, failed_count: 0, unknown_count: 0, sample_count: 0, ratio: null },
  latest_verification: null,
  latest_event: null,
  coverage: {
    complete: false,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW + 1,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [],
    observation_gaps: [{ start: NOW - 7 * DAY, end: NOW }],
  },
  enabled: true,
  blocked_reason: null,
}, NOW)
assert.equal(noRetentionCard.conclusion, '无保留数据')
assert.equal(noRetentionCard.ratioText, '—', 'fully out-of-retention never shows 0%')

// --- T14: blocked card -------------------------------------------------------
const blockedCard = resolveMonitorCardSummary({
  verification: { success_count: 3, failed_count: 0, unknown_count: 0, sample_count: 3, ratio: 1 },
  latest_verification: { state: 'SUCCESS', started_at: NOW - DAY, finished_at: NOW - DAY },
  latest_event: null,
  coverage: {
    complete: true,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW - 6 * DAY,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [{ start: NOW - DAY, end: NOW }],
    observation_gaps: [],
  },
  enabled: false,
  blocked_reason: 'CPA_TARGET_REJECTED',
}, NOW)
assert.equal(blockedCard.stateLabel, '配置异常')

// --- T14: empty copy ---------------------------------------------------------
assert.equal(MONITOR_EMPTY_COPY.noTasks, '当前没有监控任务')
assert.equal(MONITOR_EMPTY_COPY.noRuns, '该窗口内暂无验证记录')
assert.equal(MONITOR_EMPTY_COPY.noRetention, '该窗口内无保留数据')
assert.equal(MONITOR_EMPTY_COPY.zeroSample, '窗口内暂无有效验证')
assert.equal(MONITOR_EMPTY_COPY.partial, '历史数据不完整，仅基于当前保留记录展示')
assert.equal(MONITOR_EMPTY_COPY.retentionException, '存在保留例外，部分数据已按策略删失')
assert.equal(resolveMonitorEmptyCopy(0, 'noTasks'), MONITOR_EMPTY_COPY.noTasks)
assert.equal(resolveMonitorEmptyCopy(3, 'zeroSample'), MONITOR_EMPTY_COPY.zeroSample)
assert.equal(resolveMonitorEmptyCopy(3, 'noRetention'), MONITOR_EMPTY_COPY.noRetention)

// --- T14: history view ------------------------------------------------------
const history = monitorHistoryView({
  view: 'verification',
  data_revision: 11,
  as_of: NOW,
  next_cursor: 'cursor-2',
  runs: [],
  coverage: {
    complete: true,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW - 6 * DAY,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [{ start: NOW - DAY, end: NOW }],
    observation_gaps: [],
  },
}, 'verification')
assert.equal(history.sampleBadges.length, 0)
assert.ok(history.revisionLine.includes('11'))
assert.ok(history.asOfLine.includes('UTC'))
assert.equal(history.hasNextPage, true)

const mixedRuns = monitorHistoryView({
  view: 'verification',
  runs: [
    { state: 'SUCCESS', included_in_sample: true, event_at: NOW - DAY } as never,
    { state: 'SKIPPED', included_in_sample: false, event_at: NOW - 3_600_000 } as never,
    { state: 'UNKNOWN', included_in_sample: false, event_at: NOW - 1_800_000 } as never,
  ],
  next_cursor: null,
  data_revision: 4,
  as_of: NOW,
  coverage: {
    complete: false,
    requested_start: NOW - 7 * DAY,
    requested_end: NOW,
    as_of: NOW,
    collection_started_at: NOW - 6 * DAY,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [{ start: NOW - DAY, end: NOW }],
    observation_gaps: [{ start: NOW - 6 * DAY, end: NOW - 5 * DAY }],
  },
}, 'verification')
assert.equal(mixedRuns.sampleBadges.length, 1, 'verification view marks sample rows explicitly')
assert.equal(mixedRuns.eventBadges.length, 0)

// --- shared formatting regressions -------------------------------------------
assert.equal(formatMonitorRatio(0.8), '80%')
assert.equal(formatMonitorRatio(null), '—')
assert.equal(formatMonitorUtcTime(NOW), '2025-11-07 07:20:00 UTC')
assert.equal(formatMonitorAge(NOW - 3_600_000, NOW), '1 小时前')
assert.equal(monitorWindowSummary({ complete: true } as never, 0), '窗口内暂无有效验证')

console.log('monitorPages self-test OK — T13/T14 page logic asserted')
