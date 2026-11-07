/**
 * T13 offline fixtures (synthetic data only). The shape mirrors the T10 DTOs exactly so a
 * component can run against the real monitoring adapter with a patched fetch — no real
 * backend, real identity, real id/index, upstream address or credential ever appears here.
 */
import type {
  AdminMonitorCandidateListResponse,
  AdminMonitorRunPageResponse,
  AdminMonitorTaskListResponse,
} from '@/types/monitor'

export const FIXTURE_NOW_MS = 1762500000000
export const FIXTURE_DAY_MS = 86_400_000

export const adminCandidates: AdminMonitorCandidateListResponse = {
  candidates: [
    {
      candidate_id: 'candidate-token-a',
      sequence: '1',
      provider: 'codex',
      supported: true,
      unsupported_reason: null,
      models: ['gpt-5-mini', 'gpt-5'],
    },
    {
      candidate_id: 'candidate-token-b',
      sequence: '2',
      provider: 'claude',
      supported: false,
      unsupported_reason: 'UNSUPPORTED_PROVIDER',
      models: [],
    },
  ],
  model_choices: {
    'candidate-token-a': {
      'choice-1': 'gpt-5-mini',
      'choice-2': 'gpt-5',
    },
  },
}

export const adminCandidateModels = {
  candidate_id: 'candidate-token-a',
  models: ['gpt-5-mini', 'gpt-5'],
  model_choices: {
    'choice-1': 'gpt-5-mini',
    'choice-2': 'gpt-5',
  },
}

export const adminTasks: AdminMonitorTaskListResponse = {
  tasks: [
    {
      task_id: 'task-enabled',
      safe_name: '夜间验证任务',
      safe_model_display: 'codex gpt-5-mini',
      instance_key: 'instance-key-a',
      provider: 'codex',
      enabled: true,
      blocked_reason: null,
      schedule_revision: 1,
      row_version: 1,
      cron_expression: '0 0 3 * * *',
      zone_id: 'UTC',
      next_fire_at: FIXTURE_NOW_MS + 3 * 60 * 60 * 1000,
      created_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS,
      updated_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS,
    },
    {
      task_id: 'task-disabled',
      safe_name: '周末巡检',
      safe_model_display: 'codex gpt-5',
      instance_key: 'instance-key-b',
      provider: 'codex',
      enabled: false,
      blocked_reason: null,
      schedule_revision: 2,
      row_version: 3,
      cron_expression: '0 30 8 * * SAT',
      zone_id: 'Asia/Shanghai',
      next_fire_at: null,
      created_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS,
      updated_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS,
    },
    {
      task_id: 'task-blocked',
      safe_name: '上午验证',
      safe_model_display: 'codex gpt-5-mini',
      instance_key: 'instance-key-c',
      provider: 'codex',
      enabled: false,
      blocked_reason: 'CPA_TARGET_REJECTED',
      schedule_revision: 1,
      row_version: 5,
      cron_expression: '0 0 9 * * *',
      zone_id: 'UTC',
      next_fire_at: null,
      created_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS,
      updated_at: FIXTURE_NOW_MS - 3 * 60 * 60 * 1000,
    },
  ],
  total: 3,
}

function adminRun(overrides: Partial<AdminMonitorRunPageResponse['runs'][number]>): AdminMonitorRunPageResponse['runs'][number] {
  return {
    run_id: 'run-1',
    task_id: 'task-enabled',
    safe_name: '夜间验证任务',
    safe_model_display: 'codex gpt-5-mini',
    phase: 'TERMINAL',
    state: 'SUCCESS',
    dispatch_status: 'MAY_HAVE_SENT',
    scheduled_at: null,
    started_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS,
    finished_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS + 4_000,
    duration_ms: 4_000,
    reason_code: 'VERIFIED',
    safe_admin_summary: '本地校验通过',
    cpa_http_status: 200,
    event_at: FIXTURE_NOW_MS - FIXTURE_DAY_MS + 4_000,
    included_in_sample: true,
    ...overrides,
  }
}

export const adminRunsSuccess: AdminMonitorRunPageResponse = {
  task_id: 'task-enabled',
  runs: [
    adminRun({ run_id: 'run-newest' }),
    adminRun({ run_id: 'run-older', started_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS, finished_at: FIXTURE_NOW_MS - 2 * FIXTURE_DAY_MS + 5_000, duration_ms: 5_000 }),
  ],
  next_cursor: null,
  data_revision: 11,
  as_of: FIXTURE_NOW_MS,
  start: FIXTURE_NOW_MS - FIXTURE_DAY_MS * 7,
  end: FIXTURE_NOW_MS,
  view: 'events',
  snapshot_changed: false,
}

export const adminRunsPending: AdminMonitorRunPageResponse = {
  ...adminRunsSuccess,
  task_id: 'task-enabled',
  runs: [
    adminRun({
      run_id: 'run-pending',
      state: 'PENDING',
      phase: 'DISPATCHING',
      dispatch_status: 'NOT_SENT',
      started_at: FIXTURE_NOW_MS - 60_000,
      finished_at: null,
      duration_ms: null,
      reason_code: null,
      safe_admin_summary: null,
      cpa_http_status: null,
      included_in_sample: false,
    }),
  ],
}

export const adminRunsNotFoundSent: AdminMonitorRunPageResponse = {
  ...adminRunsSuccess,
  runs: [
    adminRun({
      run_id: 'run-may-have-sent',
      state: 'UNKNOWN',
      dispatch_status: 'MAY_HAVE_SENT',
      reason_code: 'SEND_FENCE_TIMEOUT',
      safe_admin_summary: '发送栅栏超时，结果未知',
      included_in_sample: false,
    }),
  ],
}

export const adminRunsStoppedDisabled: AdminMonitorRunPageResponse = {
  ...adminRunsSuccess,
  runs: [
    adminRun({
      run_id: 'run-stopped-disabled',
      state: 'SKIPPED',
      dispatch_status: 'NOT_SENT',
      reason_code: 'TASK_DISABLED',
      safe_admin_summary: '停用后不再扫描，未发送',
      included_in_sample: false,
    }),
  ],
}

export const adminRunsBlocked: AdminMonitorRunPageResponse = {
  ...adminRunsSuccess,
  runs: [
    adminRun({
      run_id: 'run-blocked',
      state: 'CONFIG_ERROR',
      dispatch_status: 'NOT_SENT',
      reason_code: 'CPA_TARGET_REJECTED',
      safe_admin_summary: '目标拒绝，配置阻塞',
      included_in_sample: false,
    }),
  ],
}
