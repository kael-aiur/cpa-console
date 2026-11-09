/**
 * Monitoring transport contracts (D7). All wire fields stay snake_case and whitelist-only:
 * no real credential identity, endpoint, host, raw request/response or secret ever crosses
 * into the browser-facing types.
 */

export type MonitorRunState = 'PENDING' | 'SKIPPED' | 'SUCCESS' | 'FAILED' | 'UNKNOWN' | 'CONFIG_ERROR'
export type MonitorRunPhase = 'CLAIMED' | 'DISPATCHING' | 'TERMINAL'
export type MonitorDispatchStatus = 'NOT_SENT' | 'MAY_HAVE_SENT'
export type MonitorView = 'verification' | 'events'

export type MonitorErrorCode =
  | 'BAD_REQUEST'
  | 'MISSING_FIELD'
  | 'UNKNOWN_FIELD'
  | 'EMPTY_BODY'
  | 'EXPECTED_REVISION_REQUIRED'
  | 'INVALID_WINDOW'
  | 'INVALID_CURSOR'
  | 'IMMUTABLE_FIELD'
  | 'PLAN_INVALID'
  | 'TASK_NOT_FOUND'
  | 'CANDIDATE_NOT_FOUND'
  | 'MODEL_CHOICE_NOT_FOUND'
  | 'REVISION_CONFLICT'
  | 'CANDIDATE_EXPIRED'
  | 'SNAPSHOT_CHANGED'
  | 'IDENTITY_INVALID'
  | 'STORAGE_UNAVAILABLE'
  | 'CPA_UNAVAILABLE'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NETWORK_ERROR'
  | 'CSRF_TOKEN_FAILED'
  | 'MALFORMED_BODY'

export interface MonitorApiError {
  code: MonitorErrorCode
  message: string
}

/** Opaque short-lived candidate id; it never encodes a real credential id/index. */
export interface AdminMonitorCandidate {
  candidate_id: string
  sequence: string
  provider: string
  supported: boolean
  unsupported_reason: string | null
  models: string[]
}

export interface AdminMonitorCandidateListResponse {
  candidates: AdminMonitorCandidate[]
  model_choices: Record<string, Record<string, string>>
}

export interface AdminMonitorCandidateModelsResponse {
  candidate_id: string
  models: string[]
  model_choices: Record<string, string>
}

export interface AdminMonitorTask {
  task_id: string
  safe_name: string
  safe_model_display: string
  instance_key: string
  provider: string
  enabled: boolean
  blocked_reason: string | null
  schedule_revision: number
  row_version: number
  cron_expression: string
  zone_id: string
  next_fire_at: number | null
  created_at: number
  updated_at: number
}

export interface AdminMonitorTaskListResponse {
  tasks: AdminMonitorTask[]
  total: number
}

/**
 * Create accepts only the backend's whitelisted safe/schedule/target-choice fields. The
 * target is fixed from `instance_key` + `model_choice_id`; real ids/indexes/models cannot
 * be passed or overwritten, and enabled stays absent/false so creation is disabled by default.
 */
export interface AdminMonitorTaskCreateRequest {
  safe_name: string
  cron_expression: string
  zone_id: string
  instance_key: string
  model_choice_id: string
}

/** Update never accepts target identity/model fields; expected_revision is mandatory. */
export interface AdminMonitorTaskUpdateRequest {
  expected_revision: number
  safe_name?: string
  cron_expression?: string
  zone_id?: string
  enabled?: boolean
}

export interface MonitorCoverageInterval {
  start: number
  end: number
}

export interface MonitorCoverageLoss extends MonitorCoverageInterval {
  conservative: boolean
}

export interface MonitorCoverage {
  requested_start: number
  requested_end: number
  as_of: number
  collection_started_at: number
  retention_revision: number
  complete_intervals: MonitorCoverageInterval[]
  loss_intervals: MonitorCoverageLoss[]
  retained_sample_intervals: MonitorCoverageInterval[]
  observation_gaps: MonitorCoverageInterval[]
  complete: boolean
}

export interface MonitorVerificationSummary {
  success_count: number
  failed_count: number
  unknown_count: number
  sample_count: number
  /** Backend-calculated ratio; null means the zero-sample denominator, not 0%. */
  ratio: number | null
}

export interface MonitorLatestInfo {
  state: MonitorRunState
  started_at: number | null
  finished_at: number | null
}

export interface MonitorTaskCard {
  task_id: string
  safe_name: string
  safe_model_display: string
  enabled: boolean
  blocked_reason: string | null
  next_fire_at: number | null
  verification: MonitorVerificationSummary
  other_states: Record<string, number>
  latest_verification: MonitorLatestInfo | null
  latest_event: MonitorLatestInfo | null
  coverage: MonitorCoverage
  as_of: number
  data_revision: number
}

export interface MonitorTaskListResponse {
  tasks: MonitorTaskCard[]
  total: number
  as_of: number
  data_revision: number
}

/** Public (login-user) history row: result and schedule views stay separated. */
export interface MonitorRun {
  state: MonitorRunState
  dispatch_status: MonitorDispatchStatus
  started_at: number | null
  finished_at: number | null
  duration_ms: number | null
  reason_code: string | null
  event_at: number
  included_in_sample: boolean
}

export interface MonitorRunPageResponse {
  safe_name: string
  safe_model_display: string
  runs: MonitorRun[]
  next_cursor: string | null
  data_revision: number
  as_of: number
  start: number
  end: number
  view: MonitorView
  coverage: MonitorCoverage
  snapshot_changed: boolean
}

/** Admin history row: only the bounded local template and integer HTTP status may pass. */
export interface AdminMonitorRun {
  run_id: string
  task_id: string
  safe_name: string
  safe_model_display: string
  phase: MonitorRunPhase
  state: MonitorRunState
  dispatch_status: MonitorDispatchStatus
  scheduled_at: number | null
  started_at: number | null
  finished_at: number | null
  duration_ms: number | null
  reason_code: string | null
  safe_admin_summary: string | null
  cpa_http_status: number | null
  event_at: number
  included_in_sample: boolean
}

export interface AdminMonitorRunPageResponse {
  task_id: string
  runs: AdminMonitorRun[]
  next_cursor: string | null
  data_revision: number
  as_of: number
  start: number
  end: number
  view: MonitorView
  snapshot_changed: boolean
}

export interface MonitorHistoryQuery {
  start?: string
  end?: string
  view?: MonitorView
  cursor?: string
  limit?: number
}

export interface MonitorCardsQuery {
  start?: string
  end?: string
}
