import type { MonitorCoverage, MonitorRunState } from '@/types/monitor'

/**
 * Monitoring display helpers (T12 / D6.1). Formatting here never re-derives statistics
 * and never reinterprets query windows in the browser timezone: every timestamp is an
 * epoch millisecond rendered as an explicit UTC absolute instant.
 */

const RATIO_SCALE = 100

/**
 * Formats the backend-computed ratio (success/sample, already respecting the D6.1
 * denominator contract). Percentages use at most two decimals with HALF_UP rounding and
 * trailing zeros stripped; null means the zero-sample denominator and renders as an
 * em dash, never as 0%.
 */
export function formatMonitorRatio(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return '—'
  // HALF_UP at two decimals without floating-point drift: 0.8005 → 80.05%.
  const scaled = ratio * RATIO_SCALE
  const shifted = scaled * 100
  const roundedHalfUp = Math.sign(shifted) * Math.round(Math.abs(shifted) + Number.EPSILON)
  const value = roundedHalfUp / 100
  // Strip trailing zeros but keep at least one integer digit (0.8 → 80%, 1 → 100%).
  return `${stripTrailingZeros(value)}%`
}

function stripTrailingZeros(value: number): string {
  const fixed = value.toFixed(2)
  return fixed.replace(/\.?0+$/, '')
}

/** Renders an epoch millisecond as a UTC absolute time, independent of the local zone. */
export function formatMonitorUtcTime(epochMs: number | null | undefined): string {
  if (epochMs === null || epochMs === undefined || !Number.isFinite(epochMs)) return '—'
  const date = new Date(epochMs)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ` +
    `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())} UTC`
  )
}

/**
 * Query boundaries are always full ISO-8601 absolute instants in UTC (Z suffix), matching
 * the backend Instant.parse contract; the browser timezone never alters a query bound.
 */
export function toMonitorIsoInstant(epochMs: number): string {
  return new Date(epochMs).toISOString()
}

export function parseMonitorIsoInstant(iso: string): number | null {
  const epochMs = Date.parse(iso)
  return Number.isNaN(epochMs) ? null : epochMs
}

/** Reads "how long ago" text without implying current health. */
export function formatMonitorAge(epochMs: number | null | undefined, nowMs: number): string {
  if (epochMs === null || epochMs === undefined || !Number.isFinite(epochMs)) return '—'
  const delta = Math.max(0, nowMs - epochMs)
  const minutes = Math.floor(delta / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

/** Localized state labels; SKIPPED and CONFIG_ERROR never read as verification failures. */
const STATE_LABELS: Record<MonitorRunState, string> = {
  PENDING: '执行中',
  SKIPPED: '已跳过',
  SUCCESS: '成功',
  FAILED: '失败',
  UNKNOWN: '结果不确定',
  CONFIG_ERROR: '配置异常',
}

export function formatMonitorState(state: MonitorRunState): string {
  return STATE_LABELS[state] ?? state
}

/**
 * Window completeness copy (D6.2): null ratio is "—", a fully complete window with zero
 * samples says "no valid verifications in window", and completely out-of-retention windows
 * say "no retained data" instead of a 0% rate.
 */
export function monitorWindowSummary(coverage: MonitorCoverage, sampleCount: number): string {
  if (coverage.complete && sampleCount === 0) return '窗口内暂无有效验证'
  if (!coverage.complete && sampleCount === 0 && coverage.retained_sample_intervals.length === 0
    && coverage.collection_started_at >= coverage.requested_end) {
    return '无保留数据'
  }
  return '不完整历史，仅基于当前保留记录'
}

export function isMonitorCoverageComplete(coverage: MonitorCoverage): boolean {
  return coverage.complete
}
