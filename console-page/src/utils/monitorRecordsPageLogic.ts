/**
 * T14 page logic (no DOM). Pure helpers for card conclusions, five-state copy, ratio/age
 * display, empty/coverage copy and history view facts — directly testable offline. No real
 * identity, admin cron detail, diagnostic or credential text ever enters these strings.
 */
import type {
  MonitorCoverage,
  MonitorLatestInfo,
  MonitorRun,
  MonitorRunState,
  MonitorTaskCard,
  MonitorVerificationSummary,
  MonitorView,
} from '@/types/monitor'
import {
  formatMonitorAge,
  formatMonitorRatio,
  formatMonitorUtcTime,
  isMonitorCoverageComplete,
  monitorWindowSummary,
} from '@/utils/monitorDisplay'

/** Localized five-conclusion + executing copy; SKIPPED/CONFIG_ERROR never read as verify failures. */
export const MONITOR_STATE_COPY: Record<MonitorRunState, string> = {
  PENDING: '执行中',
  SKIPPED: '已跳过',
  SUCCESS: '成功',
  FAILED: '失败',
  UNKNOWN: '结果不确定',
  CONFIG_ERROR: '配置异常',
}

export interface MonitorCardSummaryView {
  conclusion: string
  conclusionDetail: string
  ratioText: string
  ratioNote: string
  coverageNote: string
  stateLabel: string
  latestVerificationLine: string
  latestEventLine: string
}

function latestLine(info: MonitorLatestInfo | null): string {
  if (!info) return '暂无记录'
  const at = info.started_at ?? info.finished_at
  return `${MONITOR_STATE_COPY[info.state] ?? info.state}${at ? ` · ${formatMonitorUtcTime(at)}` : ''}`
}

/**
 * Card conclusion precedence (D6/D8): the verification sample decides the verdict; a newer
 * SKIPPED scheduling event never overwrites an older successful verification; UNKNOWN stays
 * "uncertain"; a complete empty window and a fully out-of-retention window differ.
 */
export function resolveMonitorCardSummary(card: Pick<MonitorTaskCard, 'verification' | 'latest_verification' | 'latest_event' | 'coverage' | 'enabled' | 'blocked_reason'>, nowMs: number): MonitorCardSummaryView {
  const verification: MonitorVerificationSummary = card.verification
  const complete = isMonitorCoverageComplete(card.coverage)
  const noRetention = !complete
    && verification.sample_count === 0
    && card.coverage.retained_sample_intervals.length === 0
    && card.coverage.collection_started_at >= card.coverage.requested_end

  let conclusion: string
  if (card.blocked_reason) {
    conclusion = '配置异常，已停止调度'
  } else if (card.latest_verification && card.latest_verification.state === 'PENDING') {
    conclusion = '验证执行中'
  } else if (verification.sample_count === 0 && noRetention) {
    conclusion = '无保留数据'
  } else if (verification.sample_count === 0 && complete) {
    conclusion = '窗口内暂无有效验证'
  } else if (card.latest_verification && card.latest_verification.state === 'UNKNOWN') {
    conclusion = '最近验证结果不确定'
  } else if (card.latest_verification && card.latest_verification.state === 'FAILED') {
    conclusion = '最近一次验证失败'
  } else if (card.latest_verification && card.latest_verification.state === 'SUCCESS') {
    conclusion = '最近一次验证成功'
  } else {
    conclusion = '窗口内暂无有效验证'
  }

  // Latest scheduling event is reported separately, never as the verification verdict.
  const eventState = card.latest_event ? MONITOR_STATE_COPY[card.latest_event.state] ?? card.latest_event.state : null
  const conclusionDetail = eventState && eventState !== MONITOR_STATE_COPY.SUCCESS && eventState !== MONITOR_STATE_COPY.FAILED && eventState !== MONITOR_STATE_COPY.UNKNOWN
    ? `最近调度事件：${eventState}`
    : ''

  const coverageNote = card.blocked_reason
    ? '配置异常期间不产生新的验证数据'
    : noRetention
      ? '该窗口完全超出保留范围'
      : complete
        ? ''
        : '窗口数据不完整：仅基于当前保留记录统计'

  return {
    conclusion,
    conclusionDetail,
    ratioText: formatMonitorRatio(verification.ratio),
    ratioNote: verification.sample_count === 0 ? '无样本，不计算成功率' : '成功率按后端统一分母计算',
    coverageNote,
    stateLabel: card.blocked_reason ? '配置异常' : card.enabled ? '已启用' : '已停用',
    latestVerificationLine: latestLine(card.latest_verification),
    latestEventLine: latestLine(card.latest_event),
  }
}

/** Copy for each empty/no-data situation; "no retention" never collapses into 0%. */
export const MONITOR_EMPTY_COPY = {
  noTasks: '当前没有监控任务',
  noRuns: '该窗口内暂无验证记录',
  noRetention: '该窗口内无保留数据',
  zeroSample: '窗口内暂无有效验证',
  partial: '历史数据不完整，仅基于当前保留记录展示',
  retentionException: '存在保留例外，部分数据已按策略删失',
} as const

export type MonitorEmptyKind = keyof typeof MONITOR_EMPTY_COPY

export function resolveMonitorEmptyCopy(taskCount: number, kind: MonitorEmptyKind): string {
  if (taskCount === 0) return MONITOR_EMPTY_COPY.noTasks
  return MONITOR_EMPTY_COPY[kind]
}

export interface MonitorHistoryViewModel {
  view: MonitorView
  sampleBadges: MonitorRun[]
  eventBadges: MonitorRun[]
  revisionLine: string
  asOfLine: string
  windowLine: string
  hasNextPage: boolean
  emptyCopy: string
}

/** History view model: verification/events sample marking stays explicit per view. */
export function monitorHistoryView(page: {
  view: MonitorView
  runs: MonitorRun[]
  data_revision: number
  as_of: number
  next_cursor: string | null
  coverage: MonitorCoverage
}, currentView: MonitorView): MonitorHistoryViewModel {
  const runs = page.runs
  const sampleBadges = currentView === 'verification'
    ? runs.filter((run) => run.included_in_sample)
    : []
  const eventBadges = currentView === 'events'
    ? runs.filter((run) => !run.included_in_sample)
    : []
  const complete = isMonitorCoverageComplete(page.coverage)
  const noRetention = !complete
    && runs.length === 0
    && page.coverage.retained_sample_intervals.length === 0
    && page.coverage.collection_started_at >= page.coverage.requested_end
  const emptyCopy = noRetention
    ? MONITOR_EMPTY_COPY.noRetention
    : runs.length === 0
      ? complete ? MONITOR_EMPTY_COPY.zeroSample : MONITOR_EMPTY_COPY.noRuns
      : ''
  return {
    view: currentView,
    sampleBadges,
    eventBadges,
    revisionLine: `数据版本 ${page.data_revision}`,
    asOfLine: `截至 ${formatMonitorUtcTime(page.as_of)}`,
    windowLine: monitorWindowSummary(page.coverage, runs.filter((run) => run.included_in_sample).length),
    hasNextPage: page.next_cursor !== null && page.next_cursor !== '',
    emptyCopy,
  }
}

/** Card window line: age text, never a claim about current health. */
export function monitorCardAgeLine(card: Pick<MonitorTaskCard, 'coverage'>, nowMs: number): string {
  return `数据 ${formatMonitorAge(card.coverage.as_of, nowMs)}`
}
