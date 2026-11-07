/**
 * T14 offline fixtures (synthetic data only). Mirrors the T11 DTO shapes exactly; no real
 * id/index/account/host/cron detail or credential ever appears here.
 */
import type {
  MonitorCoverage,
  MonitorRunPageResponse,
  MonitorTaskListResponse,
} from '@/types/monitor'

export const FIXTURE_NOW_MS = 1762500000000
export const FIXTURE_DAY_MS = 86_400_000

function coverage(overrides: Partial<MonitorCoverage> = {}): MonitorCoverage {
  return {
    requested_start: FIXTURE_NOW_MS - 7 * FIXTURE_DAY_MS,
    requested_end: FIXTURE_NOW_MS,
    as_of: FIXTURE_NOW_MS,
    collection_started_at: FIXTURE_NOW_MS - 6 * FIXTURE_DAY_MS,
    retention_revision: 3,
    complete_intervals: [],
    loss_intervals: [],
    retained_sample_intervals: [],
    observation_gaps: [],
    complete: true,
    ...overrides,
  }
}

export const monitorCards: MonitorTaskListResponse = {
  tasks: [
    {
      task_id: 'task-success',
      safe_name: '夜间验证任务',
      safe_model_display: 'codex gpt-5-mini',
      enabled: true,
      blocked_reason: null,
      next_fire_at: FIXTURE_NOW_MS + 3 * 60 * 60 * 1000,
      verification: { success_count: 8, failed_count: 1, unknown_count: 1, sample_count: 10, ratio: 0.8 },
      other_states: { SKIPPED: 2 },
      latest_verification: { state: 'SUCCESS', started_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS, finished_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS + 4_000 },
      latest_event: { state: 'SKIPPED', started_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000, finished_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000 + 1_000 },
      coverage: coverage(),
      as_of: FIXTURE_NOW_MS,
      data_revision: 11,
    },
    {
      task_id: 'task-disabled',
      safe_name: '周末巡检',
      safe_model_display: 'codex gpt-5',
      enabled: false,
      blocked_reason: null,
      next_fire_at: null,
      verification: { success_count: 4, failed_count: 0, unknown_count: 0, sample_count: 4, ratio: 1 },
      other_states: {},
      latest_verification: { state: 'SUCCESS', started_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS, finished_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS + 3_000 },
      latest_event: { state: 'SUCCESS', started_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS, finished_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS + 3_000 },
      coverage: coverage(),
      as_of: FIXTURE_NOW_MS,
      data_revision: 11,
    },
    {
      task_id: 'task-zero-sample',
      safe_name: '零样本窗口',
      safe_model_display: 'codex gpt-5-mini',
      enabled: true,
      blocked_reason: null,
      next_fire_at: FIXTURE_NOW_MS + 60 * 60 * 1000,
      verification: { success_count: 0, failed_count: 0, unknown_count: 0, sample_count: 0, ratio: null },
      other_states: {},
      latest_verification: null,
      latest_event: null,
      coverage: coverage(),
      as_of: FIXTURE_NOW_MS,
      data_revision: 11,
    },
    {
      task_id: 'task-partial-gap',
      safe_name: '部分缺口',
      safe_model_display: 'codex gpt-5-mini',
      enabled: true,
      blocked_reason: null,
      next_fire_at: FIXTURE_NOW_MS + 2 * 60 * 60 * 1000,
      verification: { success_count: 2, failed_count: 1, unknown_count: 0, sample_count: 3, ratio: 0.6666666666666666 },
      other_states: {},
      latest_verification: { state: 'FAILED', started_at: FIXTURE_NOW_MS - 5 * 60 * 60 * 1000, finished_at: FIXTURE_NOW_MS - 5 * 60 * 60 * 1000 + 2_000 },
      latest_event: { state: 'FAILED', started_at: FIXTURE_NOW_MS - 5 * 60 * 60 * 1000, finished_at: FIXTURE_NOW_MS - 5 * 60 * 60 * 1000 + 2_000 },
      coverage: coverage({
        complete: false,
        observation_gaps: [{ start: FIXTURE_NOW_MS - 6 * FIXTURE_DAY_MS, end: FIXTURE_NOW_MS - 5 * FIXTURE_DAY_MS }],
        loss_intervals: [{ start: FIXTURE_NOW_MS - 5 * FIXTURE_DAY_MS, end: FIXTURE_NOW_MS - 5 * FIXTURE_DAY_MS + 3_600_000, conservative: true }],
      }),
      as_of: FIXTURE_NOW_MS,
      data_revision: 11,
    },
    {
      task_id: 'task-no-retention',
      safe_name: '无保留历史',
      safe_model_display: 'codex gpt-5-mini',
      enabled: false,
      blocked_reason: 'TZDB_CERTIFICATE_INVALID',
      next_fire_at: null,
      verification: { success_count: 0, failed_count: 0, unknown_count: 0, sample_count: 0, ratio: null },
      other_states: {},
      latest_verification: null,
      latest_event: null,
      coverage: coverage({
        complete: false,
        collection_started_at: FIXTURE_NOW_MS + 1,
        retained_sample_intervals: [],
        observation_gaps: [{ start: FIXTURE_NOW_MS - 7 * FIXTURE_DAY_MS, end: FIXTURE_NOW_MS }],
      }),
      as_of: FIXTURE_NOW_MS,
      data_revision: 11,
    },
  ],
  total: 5,
  as_of: FIXTURE_NOW_MS,
  data_revision: 11,
}

function publicRun(overrides: Partial<MonitorRunPageResponse['runs'][number]>): MonitorRunPageResponse['runs'][number] {
  return {
    state: 'SUCCESS',
    dispatch_status: 'MAY_HAVE_SENT',
    started_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS,
    finished_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS + 4_000,
    duration_ms: 4_000,
    reason_code: 'VERIFIED',
    event_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS + 4_000,
    included_in_sample: true,
    ...overrides,
  }
}

export const verificationRuns: MonitorRunPageResponse = {
  safe_name: '夜间验证任务',
  safe_model_display: 'codex gpt-5-mini',
  runs: [
    publicRun({ state: 'FAILED', reason_code: 'VERIFY_FAILED', started_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000, finished_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000 + 2_000, duration_ms: 2_000, event_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000 + 2_000 }),
    publicRun({}),
  ],
  next_cursor: null,
  data_revision: 11,
  as_of: FIXTURE_NOW_MS,
  start: FIXTURE_NOW_MS - 7 * FIXTURE_DAY_MS,
  end: FIXTURE_NOW_MS,
  view: 'verification',
  coverage: coverage(),
  snapshot_changed: false,
}

export const eventRuns: MonitorRunPageResponse = {
  ...verificationRuns,
  view: 'events',
  runs: [
    publicRun({ state: 'SKIPPED', dispatch_status: 'NOT_SENT', reason_code: 'TASK_DISABLED', started_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000, finished_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000 + 1_000, duration_ms: 1_000, event_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000 + 1_000, included_in_sample: false }),
    publicRun({}),
  ],
}

export const publicRunsNoRetention: MonitorRunPageResponse = {
  ...verificationRuns,
  runs: [],
  coverage: coverage({
    complete: false,
    collection_started_at: FIXTURE_NOW_MS + 1,
    observation_gaps: [{ start: FIXTURE_NOW_MS - 7 * FIXTURE_DAY_MS, end: FIXTURE_NOW_MS }],
  }),
}
