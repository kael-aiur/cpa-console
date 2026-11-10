/**
 * T13 page logic (no DOM). Each helper is a pure function so the component template stays
 * thin and the acceptance rules are directly testable offline. All copy here is fixed
 * local Chinese text — it never echoes backend messages, upstream text or identifiers.
 */
import type { AdminMonitorCandidate, AdminMonitorTask, MonitorApiError } from '@/types/monitor'
import type { AdminMonitorRun, MonitorDispatchStatus } from '@/types/monitor'
import { formatMonitorUtcTime } from '@/utils/monitorDisplay'

/** Stable blocked-reason copy shown in task rows and edit forms. */
export const ADMIN_BLOCKED_COPY: Record<string, string> = {
  CPA_TARGET_REJECTED: '目标拒绝：请检查凭证与模型后重新启用',
  TARGET_REJECTED: '目标拒绝：请检查凭证与模型后重新启用',
  TZDB_CERTIFICATE_INVALID: '时区数据库证书失效：重新启用前需重新校验定时计划',
  IDENTITY_INVALID: '监控目标身份校验未通过，请刷新候选后重新启用',
}

/** Dispatch fence copy for run rows: never implies a definite success/failure. */
export const ADMIN_RUN_SUMMARY_COPY: Record<MonitorDispatchStatus, string> = {
  NOT_SENT: '未发送',
  MAY_HAVE_SENT: '可能已发送',
}

export function formatAdminRunSummaryLine(status: MonitorDispatchStatus): string {
  return ADMIN_RUN_SUMMARY_COPY[status] ?? status
}

export function resolveAdminBlockedCopy(reason: string | null): string {
  if (!reason) return ''
  return ADMIN_BLOCKED_COPY[reason] ?? '配置阻塞：请刷新后重新校验启用'
}

/** Local copy for a server-reported stale candidate (CANDIDATE_NOT_FOUND / CANDIDATE_EXPIRED). */
export const ADMIN_CANDIDATE_STALE_COPY = '所选候选已过期，请重新获取候选列表后再选择'

/**
 * Render only the server-provided safe label, with a local distinguishable fallback.
 * Candidate ids, sequence numbers and other opaque identity fields never appear in the UI.
 */
export function adminCandidateDisplayName(
  candidate: Pick<AdminMonitorCandidate, 'display_name' | 'provider' | 'supported'>,
  candidates: Array<Pick<AdminMonitorCandidate, 'display_name' | 'provider' | 'supported'>> = [],
): string {
  const base = candidate.display_name.trim() || candidate.provider.trim() || '受支持候选'
  const sameLabel = candidates.filter((item) => {
    const itemBase = item.display_name.trim() || item.provider.trim() || '受支持候选'
    return itemBase === base
  })
  if (sameLabel.length <= 1) return base
  const provider = candidate.provider.trim()
  const providerMatches = sameLabel.filter((item) => item.provider.trim() === provider)
  if (provider && providerMatches.length === 1) return `${base} · ${provider}`
  const occurrence = sameLabel.indexOf(candidate) + 1
  return `${base} · 候选项 ${occurrence}`
}

/** True only for the server-reported stale-candidate error codes; never a local age guess. */
export function isAdminCandidateStaleError(error: Pick<MonitorApiError, 'code'>): boolean {
  return error.code === 'CANDIDATE_NOT_FOUND' || error.code === 'CANDIDATE_EXPIRED'
}

/** Strict six-field cron shape check — local pre-check only, never a full-domain proof. */
export function isAdminCronShapeValid(expression: string): boolean {
  return expression.trim().split(/\s+/).length === 6
}

/**
 * Create payload builder: omits `enabled` so the backend default (disabled) holds; no
 * real identity, id/index or model field can enter here because the accepted keys are
 * exactly the whitelisted five.
 */
export function buildAdminPlanDraft(cronExpression: string, zoneId: string) {
  return {
    cron_expression: cronExpression.trim(),
    zone_id: zoneId.trim() || 'UTC',
  }
}

/** Human plan help: local zone display + explicit UTC absolute instant of next fire. */
export function describeAdminPlan(cronExpression: string, zoneId: string, nextFireAtMs: number | null): string {
  const shape = isAdminCronShapeValid(cronExpression) ? '六段 cron（秒 分 时 日 月 周）' : '计划待校验'
  const zone = zoneId.trim() || 'UTC'
  const next = nextFireAtMs
    ? `下次执行 ${formatMonitorUtcTime(nextFireAtMs)}（按 ${zone} 计算）`
    : '暂无下次执行时间'
  return `${shape} · ${next} · 时间均按所选时区与 UTC 绝对时间展示`
}

export interface AdminTaskStateView {
  stateLabel: string
  stateKind: 'enabled' | 'disabled' | 'blocked'
  blockedCopy: string
  nextFireText: string
}

/** Task row state: enabled/disabled/blocked are distinct; next fire stays UTC-explicit. */
export function adminTaskStateView(task: Pick<AdminMonitorTask, 'enabled' | 'blocked_reason' | 'next_fire_at'>, nowMs: number): AdminTaskStateView {
  const blockedCopy = resolveAdminBlockedCopy(task.blocked_reason)
  if (task.blocked_reason) {
    return { stateLabel: '配置阻塞', stateKind: 'blocked', blockedCopy, nextFireText: task.next_fire_at ? formatMonitorUtcTime(task.next_fire_at) : '待重新校验' }
  }
  if (!task.enabled) {
    return { stateLabel: '已停用', stateKind: 'disabled', blockedCopy: '', nextFireText: task.next_fire_at ? formatMonitorUtcTime(task.next_fire_at) : '停用后不调度' }
  }
  void nowMs
  return { stateLabel: '已启用', stateKind: 'enabled', blockedCopy: '', nextFireText: task.next_fire_at ? formatMonitorUtcTime(task.next_fire_at) : '待重新校验' }
}

export const ADMIN_EDITABLE_FIELDS = ['safe_name', 'cron_expression', 'zone_id', 'enabled'] as const

/** Revision-conflict copy used after the page refreshed to the latest row version. */
export function adminRevisionState(error: Pick<MonitorApiError, 'code'>): string {
  if (error.code === 'REVISION_CONFLICT') return '任务已被他人修改，已刷新最新版本，请重试'
  if (isAdminCandidateStaleError(error)) return ADMIN_CANDIDATE_STALE_COPY
  if (error.code === 'IDENTITY_INVALID') return '监控目标身份校验未通过，请刷新后重试'
  if (error.code === 'PLAN_INVALID') return '定时计划无效，请检查 cron 表达式或时区'
  return ''
}

/** Admin history phase copy keeps verification and scheduling facts separated. */
export function adminRunPhaseLine(run: Pick<AdminMonitorRun, 'scheduled_at' | 'started_at' | 'finished_at' | 'dispatch_status'>): string {
  const scheduled = run.scheduled_at ? `调度 ${formatMonitorUtcTime(run.scheduled_at)}` : '调度时间待定'
  const started = run.started_at ? `开始 ${formatMonitorUtcTime(run.started_at)}` : '未开始'
  const finished = run.finished_at ? `结束 ${formatMonitorUtcTime(run.finished_at)}` : '未结束'
  return `${scheduled} · ${started} · ${finished} · ${formatAdminRunSummaryLine(run.dispatch_status)}`
}

/** Admin history rows never render raw upstream text; only bounded local summaries. */
export function adminRunDisplaySummary(run: Pick<AdminMonitorRun, 'safe_admin_summary' | 'reason_code' | 'cpa_http_status'>): string {
  const parts: string[] = []
  if (run.reason_code) parts.push(run.reason_code)
  if (run.safe_admin_summary) parts.push(run.safe_admin_summary)
  if (run.cpa_http_status !== null) parts.push(`HTTP ${run.cpa_http_status}`)
  return parts.join(' · ')
}
