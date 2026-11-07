import type { ApiResponse } from '@/types/app'
import type {
  AdminMonitorCandidateListResponse,
  AdminMonitorCandidateModelsResponse,
  AdminMonitorRunPageResponse,
  AdminMonitorTask,
  AdminMonitorTaskCreateRequest,
  AdminMonitorTaskListResponse,
  AdminMonitorTaskUpdateRequest,
  MonitorCardsQuery,
  MonitorErrorCode,
  MonitorHistoryQuery,
  MonitorRunPageResponse,
  MonitorTaskListResponse,
  MonitorView,
} from '@/types/monitor'

/**
 * Monitoring API adapter (T12). Eight whitelisted endpoints only — six admin endpoints
 * under /admin/monitor plus two login-user read endpoints under /api/monitor. Reads stay
 * pure queries (GET, no verify trigger); writes always carry the session cookie and the
 * CSRF header. There is deliberately no delete, execute or re-bind call here.
 */

interface CsrfResponse {
  token: string
}

/** Stable local messages per error code; none echo request parameters or upstream text. */
const ERROR_MESSAGES: Record<MonitorErrorCode, string> = {
  BAD_REQUEST: '请求参数无效，请检查后重试',
  MISSING_FIELD: '缺少必填字段，请补全后重试',
  UNKNOWN_FIELD: '包含不支持的字段，请检查表单内容',
  EMPTY_BODY: '提交内容为空，请填写后重试',
  EXPECTED_REVISION_REQUIRED: '缺少修订版本号，请刷新后重试',
  INVALID_WINDOW: '时间窗口无效：起止必须为合法 ISO-8601 绝对时刻且开始早于结束',
  INVALID_CURSOR: '分页游标无效，请回到第一页重新加载',
  IMMUTABLE_FIELD: '监控目标不可修改，如需更换目标请另建任务',
  PLAN_INVALID: '定时计划无效，请检查 cron 表达式或时区',
  TASK_NOT_FOUND: '监控任务不存在或已被移除',
  CANDIDATE_NOT_FOUND: '候选已过期或不存在，请重新获取候选列表后再选择',
  MODEL_CHOICE_NOT_FOUND: '所选模型不存在，请重新选择',
  REVISION_CONFLICT: '任务已被他人修改，请刷新后基于最新版本重试',
  CANDIDATE_EXPIRED: '所选候选已过期，请重新获取候选列表后再选择',
  SNAPSHOT_CHANGED: '监控数据已更新，请刷新',
  IDENTITY_INVALID: '监控目标身份校验未通过，请刷新后重试',
  STORAGE_UNAVAILABLE: '本地存储暂不可用，请稍后重试',
  CPA_UNAVAILABLE: '上游服务暂不可用，请稍后重试',
  UNAUTHORIZED: '登录状态已失效，请重新登录',
  FORBIDDEN: '没有权限执行此操作',
  NETWORK_ERROR: '网络连接失败，请检查网络后重试',
  CSRF_TOKEN_FAILED: '获取安全令牌失败，请重试',
  MALFORMED_BODY: '提交内容格式无效，请重试',
}

const CSRF_HEADER = 'X-XSRF-TOKEN'
const CSRF_ENDPOINT = '/api/csrf'

export class MonitorApiError extends Error {
  readonly code: MonitorErrorCode
  readonly status: number

  constructor(code: MonitorErrorCode, status: number) {
    super(ERROR_MESSAGES[code] ?? '请求失败，请重试')
    this.name = 'MonitorApiError'
    this.code = code
    this.status = status
  }
}

let csrfTokenPromise: Promise<string> | null = null

function readErrorCode(status: number, body: { code?: unknown } | null): MonitorErrorCode {
  const code = typeof body?.code === 'string' ? body.code : null
  if (code === 'SNAPSHOT_CHANGED') return 'SNAPSHOT_CHANGED'
  if (code === 'REVISION_CONFLICT') return 'REVISION_CONFLICT'
  if (code !== null && code in ERROR_MESSAGES) return code as MonitorErrorCode
  if (status === 400) return 'BAD_REQUEST'
  if (status === 401) return 'UNAUTHORIZED'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'TASK_NOT_FOUND'
  if (status === 409) return 'SNAPSHOT_CHANGED'
  if (status === 503) return 'STORAGE_UNAVAILABLE'
  return 'NETWORK_ERROR'
}

async function parseErrorResponse(response: Response): Promise<MonitorApiError> {
  let body: { code?: unknown } | null = null
  try {
    body = (await response.clone().json()) as { code?: unknown }
  } catch {
    body = null
  }
  return new MonitorApiError(readErrorCode(response.status, body), response.status)
}

/** Same-origin session cookie on every request, exactly like the existing adapters. */
async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, { ...init, credentials: 'include' })
  } catch {
    throw new MonitorApiError('NETWORK_ERROR', 0)
  }
  if (!response.ok) throw await parseErrorResponse(response)
  try {
    return (await response.json()) as T
  } catch {
    throw new MonitorApiError('NETWORK_ERROR', response.status)
  }
}

async function getCsrfToken(): Promise<string> {
  if (!csrfTokenPromise) {
    csrfTokenPromise = requestJson<CsrfResponse>(CSRF_ENDPOINT).then((data) => data.token)
    csrfTokenPromise.catch(() => {
      csrfTokenPromise = null
    })
  }
  try {
    return await csrfTokenPromise
  } catch {
    throw new MonitorApiError('CSRF_TOKEN_FAILED', 0)
  }
}

async function mutate<T>(path: string, method: 'POST' | 'PATCH', body: unknown): Promise<T> {
  const token = await getCsrfToken()
  return requestJson<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json', [CSRF_HEADER]: token },
    body: JSON.stringify(body),
  })
}

function buildHistoryQuery(query: MonitorHistoryQuery): string {
  const params = new URLSearchParams()
  if (query.start !== undefined) params.set('start', query.start)
  if (query.end !== undefined) params.set('end', query.end)
  if (query.view !== undefined) params.set('view', query.view)
  if (query.cursor !== undefined && query.cursor !== '') params.set('cursor', query.cursor)
  if (query.limit !== undefined) params.set('limit', String(query.limit))
  return params.toString()
}

function buildCardsQuery(query: MonitorCardsQuery): string {
  const params = new URLSearchParams()
  if (query.start !== undefined) params.set('start', query.start)
  if (query.end !== undefined) params.set('end', query.end)
  return params.toString()
}

// ---------------------------------------------------------------------------
// Admin endpoints (role=admin, CSRF on writes)
// ---------------------------------------------------------------------------

export function getAdminMonitorCandidates(): Promise<AdminMonitorCandidateListResponse> {
  return requestJson('/admin/monitor/credentials')
}

export function getAdminMonitorCandidateModels(candidateId: string): Promise<AdminMonitorCandidateModelsResponse> {
  return requestJson(`/admin/monitor/credentials/${encodeURIComponent(candidateId)}/models`)
}

export function getAdminMonitorTasks(): Promise<AdminMonitorTaskListResponse> {
  return requestJson('/admin/monitor/tasks')
}

/**
 * Creates a task in its default disabled state: `enabled` is omitted so the backend
 * defaults to false. The immutable target is fixed from the opaque candidate token and
 * model choice id — real ids/indexes/models cannot be supplied here.
 */
export function createAdminMonitorTask(payload: AdminMonitorTaskCreateRequest): Promise<AdminMonitorTask> {
  return mutate('/admin/monitor/tasks', 'POST', payload)
}

/**
 * Edits only safe display/schedule/enabled fields. `expected_revision` is mandatory and
 * a stale value comes back as 409 REVISION_CONFLICT without overwriting anything.
 */
export function updateAdminMonitorTask(
  taskId: string,
  payload: AdminMonitorTaskUpdateRequest,
): Promise<AdminMonitorTask> {
  return mutate(`/admin/monitor/tasks/${encodeURIComponent(taskId)}`, 'PATCH', payload)
}

export function getAdminMonitorTaskRuns(
  taskId: string,
  query: MonitorHistoryQuery = {},
): Promise<AdminMonitorRunPageResponse> {
  return requestJson(`/admin/monitor/tasks/${encodeURIComponent(taskId)}/runs?${buildHistoryQuery(query)}`)
}

// ---------------------------------------------------------------------------
// Login-user read endpoints (any authenticated user, GET only)
// ---------------------------------------------------------------------------

export function getMonitorTaskCards(query: MonitorCardsQuery = {}): Promise<MonitorTaskListResponse> {
  return requestJson(`/api/monitor/tasks?${buildCardsQuery(query)}`)
}

export function getMonitorTaskRuns(
  taskId: string,
  query: MonitorHistoryQuery = {},
): Promise<MonitorRunPageResponse> {
  return requestJson(`/api/monitor/tasks/${encodeURIComponent(taskId)}/runs?${buildHistoryQuery(query)}`)
}

// ---------------------------------------------------------------------------
// Response wrappers (existing console pattern)
// ---------------------------------------------------------------------------

function wrap<T>(data: T): ApiResponse<T> {
  return { code: 0, message: 'ok', data }
}

export function fetchAdminMonitorCandidates(): Promise<ApiResponse<AdminMonitorCandidateListResponse>> {
  return getAdminMonitorCandidates().then(wrap)
}

export function fetchAdminMonitorCandidateModels(candidateId: string): Promise<ApiResponse<AdminMonitorCandidateModelsResponse>> {
  return getAdminMonitorCandidateModels(candidateId).then(wrap)
}

export function fetchAdminMonitorTasks(): Promise<ApiResponse<AdminMonitorTaskListResponse>> {
  return getAdminMonitorTasks().then(wrap)
}

export function fetchCreateAdminMonitorTask(payload: AdminMonitorTaskCreateRequest): Promise<ApiResponse<AdminMonitorTask>> {
  return createAdminMonitorTask(payload).then(wrap)
}

export function fetchUpdateAdminMonitorTask(
  taskId: string,
  payload: AdminMonitorTaskUpdateRequest,
): Promise<ApiResponse<AdminMonitorTask>> {
  return updateAdminMonitorTask(taskId, payload).then(wrap)
}

export function fetchAdminMonitorTaskRuns(
  taskId: string,
  query: MonitorHistoryQuery = {},
): Promise<ApiResponse<AdminMonitorRunPageResponse>> {
  return getAdminMonitorTaskRuns(taskId, query).then(wrap)
}

export function fetchMonitorTaskCards(query: MonitorCardsQuery = {}): Promise<ApiResponse<MonitorTaskListResponse>> {
  return getMonitorTaskCards(query).then(wrap)
}

export function fetchMonitorTaskRuns(
  taskId: string,
  query: MonitorHistoryQuery = {},
): Promise<ApiResponse<MonitorRunPageResponse>> {
  return getMonitorTaskRuns(taskId, query).then(wrap)
}

export function normalizeMonitorError(error: unknown): MonitorApiError {
  if (error instanceof MonitorApiError) return error
  return new MonitorApiError('NETWORK_ERROR', 0)
}

export function isSnapshotChangedError(error: unknown): boolean {
  return normalizeMonitorError(error).code === 'SNAPSHOT_CHANGED'
}

export function isRevisionConflictError(error: unknown): boolean {
  return normalizeMonitorError(error).code === 'REVISION_CONFLICT'
}

export type { MonitorView }
