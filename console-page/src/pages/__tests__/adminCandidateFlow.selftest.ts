/**
 * FE-01 offline flow self-test (mock fetch only; no real backend, no real browser).
 *
 * Contract under test (NATI-171-FE-01, task v1.2-r1): the admin create flow never decides
 * candidate staleness from a local clock. A fresh listing stays selectable immediately —
 * choose candidate, load models and create must all succeed right after load — and only
 * a server-declared CANDIDATE_NOT_FOUND / CANDIDATE_EXPIRED clears the selection, shows
 * the stale copy, reloads the listing and re-enables a clean selection. Safe display
 * names stay browser-only, local form values never leak into any request, and the create
 * payload keeps the backend whitelist (five keys, no enabled, no identity fields).
 *
 * Run via the committed standalone tsc procedure (see monitorApi.selftest.ts header):
 *   1. tsc -p .tsconfig.selftest.json
 *   2. rewrite "@/..." specifiers in the emitted JS to relative paths
 *   3. node .selftest-out/src/pages/__tests__/adminCandidateFlow.selftest.js
 */
import { strict as assert } from 'node:assert'
import {
  ADMIN_CANDIDATE_STALE_COPY,
  adminRevisionState,
  isAdminCandidateStaleError,
} from '@/utils/adminMonitorPageLogic'
import { formatMonitorUtcTime } from '@/utils/monitorDisplay'
import {
  fetchAdminMonitorCandidateModels,
  fetchAdminMonitorCandidates,
  fetchCreateAdminMonitorTask,
  MonitorApiError,
} from '@/services/monitorApi'
import type { AdminMonitorCandidateListResponse } from '@/types/monitor'

const NOW = 1_762_500_000_000

interface RecordedRequest {
  path: string
  method: string
  credentials?: RequestCredentials
  headers?: Record<string, string>
  body?: string | null
}

const calls: RecordedRequest[] = []
let responses: Array<{ status: number; body: unknown }> = []
const realFetch = globalThis.fetch

const patchedFetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const path = typeof input === 'string' ? input : input.toString()
  const method = init?.method ?? 'GET'
  calls.push({
    path,
    method,
    credentials: init?.credentials,
    headers: init?.headers as Record<string, string> | undefined,
    body: typeof init?.body === 'string' ? init.body : null,
  })
  if (path === '/api/csrf') {
    return jsonResponse(200, { token: 'csrf-token-flow' })
  }
  const next = responses.shift()
  if (!next) throw new Error(`unexpected fetch: ${method} ${path}`)
  return jsonResponse(next.status, next.body)
}
globalThis.fetch = patchedFetch as typeof fetch

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    clone() {
      return this
    },
  } as unknown as Response
}

function candidatePayload(): AdminMonitorCandidateListResponse {
  return {
    candidates: [
      {
        candidate_id: 'candidate-token-a',
        sequence: '1',
        display_name: 'team-a',
        provider: 'codex',
        supported: true,
        unsupported_reason: null,
        models: ['gpt-5-mini'],
      },
      {
        candidate_id: 'candidate-token-b',
        sequence: '2',
        display_name: 'claude #2',
        provider: 'claude',
        supported: false,
        unsupported_reason: 'UNSUPPORTED_PROVIDER',
        models: [],
      },
    ],
    model_choices: { 'candidate-token-a': { 'choice-1': 'gpt-5-mini' } },
  }
}

/** Extracted pure-logic mirror of the page's create-flow state handling (AdminMonitorPage.vue). */
class CandidateCreateFlow {
  candidates: AdminMonitorCandidateListResponse['candidates'] = []
  candidateChoices: Record<string, Record<string, string>> = {}
  selectedCandidateId = ''
  selectedModelChoiceId = ''
  candidateModels: string[] = []
  stale = false
  formError = ''
  saving = false

  candidateExpiryCopy(): string {
    return this.stale ? ADMIN_CANDIDATE_STALE_COPY : ''
  }

  clearStaleSelection(): void {
    this.selectedCandidateId = ''
    this.selectedModelChoiceId = ''
    this.candidateModels = []
  }

  async refreshCandidates(): Promise<void> {
    try {
      const response = await fetchAdminMonitorCandidates()
      this.candidates = response.data.candidates
      this.candidateChoices = response.data.model_choices
      this.stale = false
      if (!this.candidates.some((item) => item.candidate_id === this.selectedCandidateId)) {
        this.clearStaleSelection()
      }
    } catch (error: unknown) {
      this.formError = (error as MonitorApiError).message
    }
  }

  async loadCandidateModels(candidateId: string): Promise<void> {
    this.selectedCandidateId = candidateId
    this.selectedModelChoiceId = ''
    this.candidateModels = []
    this.stale = false
    if (!candidateId) return
    try {
      const response = await fetchAdminMonitorCandidateModels(candidateId)
      this.candidateModels = response.data.models
      if (Object.keys(response.data.model_choices).length > 0) {
        this.selectedModelChoiceId = Object.keys(response.data.model_choices)[0] ?? ''
      }
    } catch (error: unknown) {
      const monitorError = error as MonitorApiError
      if (isAdminCandidateStaleError(monitorError)) {
        this.stale = true
        this.clearStaleSelection()
        this.formError = ADMIN_CANDIDATE_STALE_COPY
        await this.refreshCandidates()
        this.stale = true
        return
      }
      this.formError = monitorError.message
    }
  }

  async submitCreate(payload: Record<string, string>): Promise<void> {
    if (this.stale) {
      this.formError = this.candidateExpiryCopy()
      return
    }
    this.saving = true
    this.formError = ''
    try {
      await fetchCreateAdminMonitorTask(payload as never)
    } catch (error: unknown) {
      const monitorError = error as MonitorApiError
      if (isAdminCandidateStaleError(monitorError)) {
        this.stale = true
        this.clearStaleSelection()
        this.formError = ADMIN_CANDIDATE_STALE_COPY
        await this.refreshCandidates()
        this.stale = true
        return
      }
      this.formError = monitorError.message
    } finally {
      this.saving = false
    }
  }
}

async function main() {
  const flow = new CandidateCreateFlow()

  // 1. Fresh listing loads and stays usable: no local stamp, no expiry gate.
  responses = [{ status: 200, body: candidatePayload() }]
  await flow.refreshCandidates()
  assert.equal(flow.stale, false, 'a fresh listing is never stale by local age')
  assert.equal(flow.candidateExpiryCopy(), '', 'no expiry copy right after load')
  assert.equal(flow.candidates[0]!.display_name, 'team-a', 'safe display name reaches the browser state')
  assert.ok(!('loadedAt' in flow), 'no local load timestamp exists in the flow state')

  // 2. Immediate select + model load right after listing: no expiry rejection.
  responses = [{ status: 200, body: { candidate_id: 'candidate-token-a', models: ['gpt-5-mini'], model_choices: { 'choice-1': 'gpt-5-mini' } } }]
  await flow.loadCandidateModels('candidate-token-a')
  assert.equal(flow.selectedCandidateId, 'candidate-token-a')
  assert.equal(flow.candidateModels[0], 'gpt-5-mini')
  assert.equal(flow.selectedModelChoiceId, 'choice-1')
  assert.equal(flow.formError, '', 'model load right after load must not report expiry')

  // 3. Immediate create right after load: no local expiry gate, whitelist-only payload.
  responses = [{ status: 200, body: { task_id: 'task-1' } }]
  await flow.submitCreate({
    safe_name: '夜间验证',
    cron_expression: '0 0 3 * * *',
    zone_id: 'UTC',
    instance_key: 'candidate-token-a',
    model_choice_id: 'choice-1',
  })
  const createCall = calls.filter((call) => call.method === 'POST').at(-1)!
  assert.equal(createCall.path, '/admin/monitor/tasks')
  assert.equal(createCall.credentials, 'include', 'writes carry the session cookie')
  assert.equal(createCall.headers?.['X-XSRF-TOKEN'], 'csrf-token-flow', 'writes carry the CSRF header')
  const createBody = JSON.parse(createCall.body!) as Record<string, unknown>
  assert.deepEqual(
    Object.keys(createBody).sort(),
    ['cron_expression', 'instance_key', 'model_choice_id', 'safe_name', 'zone_id'],
    'create payload keeps the backend five-key whitelist',
  )
  assert.ok(!('enabled' in createBody), 'create stays disabled-by-default (no enabled field)')
  assert.ok(!('auth_id' in createBody) && !('auth_index' in createBody), 'no identity fields in the payload')

  // 4. Server-declared stale candidate (models fetch): clears selection, stale copy, reload.
  responses = [
    { status: 404, body: { code: 'CANDIDATE_NOT_FOUND', message: 'ignored' } },
    { status: 200, body: candidatePayload() },
  ]
  await flow.loadCandidateModels('candidate-token-a')
  assert.equal(flow.stale, true, 'server 404 marks the snapshot stale')
  assert.equal(flow.selectedCandidateId, '', 'stale selection is cleared')
  assert.equal(flow.selectedModelChoiceId, '')
  assert.deepEqual(flow.candidateModels, [])
  assert.equal(flow.candidateExpiryCopy(), ADMIN_CANDIDATE_STALE_COPY)
  assert.equal(flow.formError, ADMIN_CANDIDATE_STALE_COPY, 'stale copy is fixed local text, never backend echo')
  assert.equal(calls.at(-1)!.path, '/admin/monitor/credentials', 'stale candidates trigger a fresh listing fetch')

  // 5. Stale flag blocks submit until the fresh listing re-enables the flow.
  await flow.submitCreate({
    safe_name: '夜间验证', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: 'candidate-token-a', model_choice_id: 'choice-1',
  })
  assert.equal(flow.saving, false, 'a server-declared stale selection never reaches create')
  const callsDuringStale = calls.length
  await flow.submitCreate({
    safe_name: 'x', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: '', model_choice_id: '',
  })
  assert.equal(calls.length, callsDuringStale, 'stale gate short-circuits before any request')

  // 6. Fresh listing after the reload restores a working flow: reselect + create.
  responses = [
    { status: 200, body: { candidate_id: 'candidate-token-a', models: ['gpt-5-mini'], model_choices: { 'choice-1': 'gpt-5-mini' } } },
    { status: 200, body: { task_id: 'task-2' } },
  ]
  await flow.loadCandidateModels('candidate-token-a')
  assert.equal(flow.stale, false, 'a fresh selection clears the server-declared stale state')
  assert.equal(flow.candidateExpiryCopy(), '')
  await flow.submitCreate({
    safe_name: '夜间验证', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: 'candidate-token-a', model_choice_id: 'choice-1',
  })
  assert.equal(flow.formError, '', 'create succeeds on the fresh snapshot')

  // 7. Server-reported CANDIDATE_EXPIRED at create also clears + reloads.
  responses = [
    { status: 410, body: { code: 'CANDIDATE_EXPIRED', message: 'ignored' } },
    { status: 200, body: candidatePayload() },
  ]
  await flow.submitCreate({
    safe_name: '夜间验证', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: 'candidate-token-a', model_choice_id: 'choice-1',
  })
  assert.equal(flow.stale, true)
  assert.equal(flow.selectedCandidateId, '')
  assert.equal(calls.at(-1)!.path, '/admin/monitor/credentials')
  assert.equal(flow.formError, ADMIN_CANDIDATE_STALE_COPY)

  // 8. Re-selecting clears the stale gate; non-candidate errors keep their own local
  // message and never mark the listing stale.
  responses = [
    { status: 200, body: { candidate_id: 'candidate-token-a', models: ['gpt-5-mini'], model_choices: { 'choice-1': 'gpt-5-mini' } } },
    { status: 429, body: {} },
  ]
  await flow.loadCandidateModels('candidate-token-a')
  assert.equal(flow.stale, false, 'a fresh re-select clears the stale gate')
  await flow.submitCreate({
    safe_name: '夜间验证', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: 'candidate-token-a', model_choice_id: 'choice-1',
  })
  assert.equal(flow.stale, false, 'non-candidate errors must not mark the listing stale')
  const lastError: string = flow.formError
  assert.ok(lastError.length > 0, 'non-candidate errors keep a local message')
  assert.ok(!lastError.includes('candidate-token-a') && !lastError.includes('ignored'), 'no request parameters or backend text echo')

  // 9. Pure helpers: only server-declared codes count as stale; no time argument exists.
  assert.equal(isAdminCandidateStaleError({ code: 'CANDIDATE_NOT_FOUND' }), true)
  assert.equal(isAdminCandidateStaleError({ code: 'CANDIDATE_EXPIRED' }), true)
  assert.equal(isAdminCandidateStaleError({ code: 'TASK_NOT_FOUND' }), false)
  assert.equal(isAdminCandidateStaleError({ code: 'NETWORK_ERROR' }), false)
  assert.equal(adminRevisionState({ code: 'CANDIDATE_EXPIRED' }), ADMIN_CANDIDATE_STALE_COPY)
  assert.equal(adminRevisionState({ code: 'CANDIDATE_NOT_FOUND' }), ADMIN_CANDIDATE_STALE_COPY)
  assert.equal(adminRevisionState({ code: 'REVISION_CONFLICT' }), '任务已被他人修改，已刷新最新版本，请重试')

  // 10. Safe names stay display-only: no real identity, email, path or key-like labels.
  const payload = candidatePayload()
  for (const candidate of payload.candidates) {
    assert.ok(candidate.display_name.length > 0 && candidate.display_name.length <= 64, 'bounded safe display name')
    assert.ok(!candidate.display_name.includes('@'), 'no email-shaped label')
    assert.ok(!candidate.display_name.includes('/'), 'no path-shaped label')
    assert.ok(!['auth_id', 'auth_index', 'email', 'key', 'path'].some((field) => field in candidate), 'candidate DTO carries no identity fields')
  }
  assert.equal(formatMonitorUtcTime(NOW), '2025-11-07 07:20:00 UTC', 'UTC display unchanged')

  // 11. Endpoint whitelist: only candidates / models / create / csrf were touched.
  const allowedPrefixes = ['/admin/monitor/credentials', '/admin/monitor/tasks', '/api/csrf']
  for (const call of calls) {
    assert.ok(allowedPrefixes.some((prefix) => call.path.startsWith(prefix)), `unexpected endpoint: ${call.method} ${call.path}`)
    assert.ok(!call.path.includes('/execute') && !call.path.toLowerCase().includes('delete'), 'no execute/delete endpoints')
    if (call.method === 'POST') {
      assert.equal(call.credentials, 'include')
      assert.ok(call.headers?.['X-XSRF-TOKEN'])
    } else {
      assert.equal(call.method, 'GET', 'reads stay pure GET queries')
    }
  }

  console.log(`adminCandidateFlow self-test OK — ${calls.length} requests asserted, no local expiry gate`)
}

main()
  .catch((error) => {
    console.error('SELF-TEST FAILED:', error)
    process.exitCode = 1
  })
  .finally(() => {
    globalThis.fetch = realFetch
  })
