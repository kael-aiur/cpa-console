/**
 * T12 offline self-test (mock fetch only; no real backend). The app tsconfig excludes
 * __tests__ from vue-tsc, so it compiles and runs standalone with the committed project
 * TypeScript, without adding a test framework. Every assertion below must hold or the
 * process exits non-zero.
 *
 * Recorded run procedure (console-page/):
 *   1. write .tsconfig.selftest.json with module ES2020, moduleResolution Bundler,
 *      strict, types node, outDir .selftest-out, rootDir ".", paths @/* -> src/* and
 *      include this file plus monitorApi.ts, monitorDisplay.ts, useMonitorQueryState.ts,
 *      monitor.ts, app.ts
 *   2. ./node_modules/.bin/tsc -p .tsconfig.selftest.json
 *   3. rewrite "@/..." specifiers in .selftest-out JS output to relative paths with
 *      .js suffixes (for example "@/services/monitorApi" becomes "../services/monitorApi.js"
 *      from src/composables/ and "./services/monitorApi.js" from src/services/__tests__/)
 *   4. node .selftest-out/src/services/__tests__/monitorApi.selftest.js
 */
import { strict as assert } from 'node:assert'
import {
  fetchAdminMonitorCandidates,
  fetchAdminMonitorCandidateModels,
  fetchAdminMonitorTasks,
  fetchAdminMonitorTaskRuns,
  fetchCreateAdminMonitorTask,
  fetchMonitorTaskCards,
  fetchMonitorTaskRuns,
  fetchUpdateAdminMonitorTask,
  MonitorApiError,
} from '@/services/monitorApi'
import { useMonitorQueryState } from '@/composables/useMonitorQueryState'
import { formatMonitorRatio, formatMonitorUtcTime, toMonitorIsoInstant } from '@/utils/monitorDisplay'
import type { AdminMonitorTaskListResponse } from '@/types/monitor'

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

// Patch global fetch with a fixture router (no real network, no real backend).
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
    return jsonResponse(200, { token: 'csrf-token-1' })
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

async function main() {
  // 1. Candidate list: GET-only, snake_case payload passes through.
  responses = [{ status: 200, body: { candidates: [{ candidate_id: 'tok-1', sequence: '1', provider: 'codex', supported: true, unsupported_reason: null, models: ['gpt-x'] }], model_choices: { 'tok-1': { 'choice-1': 'gpt-x' } } } }]
  const candidates = await fetchAdminMonitorCandidates()
  assert.equal(calls.at(-1)!.method, 'GET')
  assert.equal(calls.at(-1)!.credentials, 'include')
  assert.equal(candidates.data.candidates[0].candidate_id, 'tok-1')
  assert.equal(candidates.data.model_choices['tok-1']['choice-1'], 'gpt-x')

  // 2. Candidate models endpoint path encoding.
  responses = [{ status: 200, body: { candidate_id: 'tok-1', models: ['gpt-x'], model_choices: { 'choice-1': 'gpt-x' } } }]
  const models = await fetchAdminMonitorCandidateModels('tok-1')
  assert.ok(calls.at(-1)!.path.startsWith('/admin/monitor/credentials/tok-1/models'))
  assert.deepEqual(models.data.models, ['gpt-x'])

  // 3. Admin task list passthrough.
  const taskPayload: AdminMonitorTaskListResponse = {
    tasks: [{
      task_id: 'task-1', safe_name: '夜间验证', safe_model_display: 'codex gpt-x', instance_key: 'ik-1',
      provider: 'codex', enabled: false, blocked_reason: null, schedule_revision: 1, row_version: 1,
      cron_expression: '0 0 3 * * *', zone_id: 'UTC', next_fire_at: 1762500000000,
      created_at: 1762400000000, updated_at: 1762400000000,
    }],
    total: 1,
  }
  responses = [{ status: 200, body: taskPayload }]
  const tasks = await fetchAdminMonitorTasks()
  assert.equal(tasks.data.total, 1)
  assert.equal(tasks.data.tasks[0].safe_name, '夜间验证')

  // 4. Create: POST with CSRF header + cookie; disabled default; target fixed via tokens.
  responses = [{ status: 200, body: taskPayload.tasks[0] }]
  await fetchCreateAdminMonitorTask({
    safe_name: '夜间验证', cron_expression: '0 0 3 * * *', zone_id: 'UTC',
    instance_key: 'tok-1', model_choice_id: 'choice-1',
  })
  const createCall = calls.find((c) => c.method === 'POST')!
  assert.equal(createCall.path, '/admin/monitor/tasks')
  assert.equal(createCall.credentials, 'include')
  assert.equal(createCall.headers?.['X-XSRF-TOKEN'], 'csrf-token-1')
  const createBody = JSON.parse(createCall.body!) as Record<string, unknown>
  assert.deepEqual(Object.keys(createBody).sort(), ['cron_expression', 'instance_key', 'model_choice_id', 'safe_name', 'zone_id'])
  assert.ok(!('enabled' in createBody), 'create must not send enabled=true; omission defaults to disabled')
  assert.ok(!('task_id' in createBody) && !('route_model' in createBody) && !('auth_index' in createBody), 'no real id/index/model overrides')

  // 5. Update: PATCH carries expected_revision and CSRF; no identity/model fields possible.
  responses = [{ status: 200, body: { ...taskPayload.tasks[0], enabled: true, row_version: 2 } }]
  await fetchUpdateAdminMonitorTask('task-1', { expected_revision: 1, enabled: true })
  const patchCall = calls.find((c) => c.method === 'PATCH')!
  assert.equal(patchCall.path, '/admin/monitor/tasks/task-1')
  assert.equal(patchCall.headers?.['X-XSRF-TOKEN'], 'csrf-token-1')
  const patchBody = JSON.parse(patchCall.body!) as Record<string, unknown>
  assert.equal(patchBody.expected_revision, 1)
  assert.deepEqual(Object.keys(patchBody).filter((k) => k !== 'expected_revision'), ['enabled'])

  // 6. Admin history page with window/view/cursor/limit.
  responses = [{
    status: 200,
    body: {
      task_id: 'task-1', runs: [], next_cursor: 'cursor-2', data_revision: 7, as_of: 1762500000000,
      start: 1762413600000, end: 1762500000000, view: 'events', snapshot_changed: false,
    },
  }]
  const startIso = toMonitorIsoInstant(1762413600000)
  const endIso = toMonitorIsoInstant(1762500000000)
  const adminPage = await fetchAdminMonitorTaskRuns('task-1', { start: startIso, end: endIso, view: 'events', cursor: '', limit: 20 })
  assert.ok(calls.at(-1)!.path.includes('start=') && calls.at(-1)!.path.includes('view=events'))
  assert.ok(!calls.at(-1)!.path.includes('cursor='), 'empty cursor is omitted')
  assert.equal(adminPage.data.next_cursor, 'cursor-2')
  assert.equal(adminPage.data.snapshot_changed, false)

  // 7. Public cards: pure GET query with paired absolute window.
  responses = [{
    status: 200,
    body: {
      tasks: [], total: 0, as_of: 1762500000000, data_revision: 7,
    },
  }]
  const cards = await fetchMonitorTaskCards({ start: startIso, end: endIso })
  assert.ok(calls.at(-1)!.path.startsWith('/api/monitor/tasks?'))
  assert.equal(cards.data.data_revision, 7)

  // 8. Public runs with cursor pagination.
  responses = [{
    status: 200,
    body: {
      safe_name: '夜间验证', safe_model_display: 'codex gpt-x', runs: [], next_cursor: null,
      data_revision: 7, as_of: 1762500000000, start: 1762413600000, end: 1762500000000,
      view: 'verification', coverage: {
        requested_start: 1762413600000, requested_end: 1762500000000, as_of: 1762500000000,
        collection_started_at: 1760000000000, retention_revision: 3, complete_intervals: [],
        loss_intervals: [], retained_sample_intervals: [], observation_gaps: [], complete: true,
      },
      snapshot_changed: false,
    },
  }]
  const publicPage = await fetchMonitorTaskRuns('task-1', { view: 'verification', cursor: 'cursor-2', limit: 100 })
  assert.ok(calls.at(-1)!.path.includes('cursor=cursor-2'))
  assert.equal(publicPage.data.view, 'verification')

  // 9. Error mapping: SNAPSHOT_CHANGED keeps its local code/message.
  responses = [{ status: 409, body: { code: 'SNAPSHOT_CHANGED', message: '监控数据已更新，请刷新' } }]
  await assert.rejects(
    () => fetchMonitorTaskRuns('task-1', {}),
    (error: unknown) => {
      const monitorError = error as MonitorApiError
      return monitorError.code === 'SNAPSHOT_CHANGED' && monitorError.status === 409
        && monitorError.message.includes('监控数据已更新')
    },
  )

  // 10. Revision conflict vs snapshot-changed stay distinct.
  responses = [{ status: 409, body: { code: 'REVISION_CONFLICT', message: '任务已被他人修改' } }]
  await assert.rejects(
    () => fetchUpdateAdminMonitorTask('task-1', { expected_revision: 1 }),
    (error: unknown) => (error as MonitorApiError).code === 'REVISION_CONFLICT',
  )

  // 11. Local fallbacks for anonymous/forbidden/not-found/unavailable.
  responses = [{ status: 401, body: null }]
  await assert.rejects(() => fetchMonitorTaskCards(), (e: unknown) => (e as MonitorApiError).code === 'UNAUTHORIZED')
  responses = [{ status: 403, body: null }]
  await assert.rejects(() => fetchAdminMonitorTasks(), (e: unknown) => (e as MonitorApiError).code === 'FORBIDDEN')
  responses = [{ status: 404, body: null }]
  await assert.rejects(() => fetchMonitorTaskRuns('missing', {}), (e: unknown) => (e as MonitorApiError).code === 'TASK_NOT_FOUND')
  responses = [{ status: 503, body: null }]
  await assert.rejects(() => fetchAdminMonitorTasks(), (e: unknown) => (e as MonitorApiError).code === 'STORAGE_UNAVAILABLE')
  // 400 without a known code stays BAD_REQUEST (no parameter echo).
  responses = [{ status: 400, body: { code: 'SOMETHING_LOCAL' } }]
  await assert.rejects(() => fetchMonitorTaskCards({ start: 'bad' }), (e: unknown) => (e as MonitorApiError).code === 'BAD_REQUEST')

  // 12. Ratio display: 8/1/1 +2 skipped → 80%; zero/null → '—'; HALF_UP strip zeros.
  assert.equal(formatMonitorRatio(0.8), '80%')
  assert.equal(formatMonitorRatio(null), '—')
  assert.equal(formatMonitorRatio(undefined), '—')
  assert.equal(formatMonitorRatio(1), '100%')
  assert.equal(formatMonitorRatio(0.8005), '80.05%')
  assert.equal(formatMonitorRatio(0.885), '88.5%')
  assert.equal(formatMonitorRatio(0), '0%')

  // 13. UTC absolute-time display; browser timezone must not change it.
  assert.equal(formatMonitorUtcTime(1762500000000), '2025-11-07 07:20:00 UTC')
  assert.equal(formatMonitorUtcTime(null), '—')
  assert.equal(toMonitorIsoInstant(1762500000000), '2025-11-07T07:20:00.000Z')

  // 14. Shared state: SNAPSHOT_CHANGED drops cursor/page, keeps the window.
  const state = useMonitorQueryState()
  assert.ok(state.setDefaultWindow(1762500000000))
  responses = [{ status: 200, body: { safe_name: 'x', safe_model_display: 'm', runs: [{ state: 'SUCCESS', dispatch_status: 'MAY_HAVE_SENT', started_at: 1, finished_at: 2, duration_ms: 1, reason_code: 'VERIFIED', event_at: 1, included_in_sample: true }], next_cursor: 'c9', data_revision: 1, as_of: 1762500000000, start: 1762413600000, end: 1762500000000, view: 'events', coverage: { requested_start: 1762413600000, requested_end: 1762500000000, as_of: 1762500000000, collection_started_at: 1760000000000, retention_revision: 1, complete_intervals: [], loss_intervals: [], retained_sample_intervals: [], observation_gaps: [], complete: true }, snapshot_changed: false } }]
  const firstPage = await state.loadPublicPage('task-1', (taskId, q) => fetchMonitorTaskRuns(taskId, q).then((r) => r.data))
  assert.equal(firstPage.nextCursor, 'c9')
  assert.equal(state.cursor.value, 'c9')
  const keptWindow = { ...(state.window.value as { startIso: string; endIso: string }) }
  responses = [{ status: 409, body: { code: 'SNAPSHOT_CHANGED', message: '监控数据已更新，请刷新' } }]
  const secondPage = await state.loadNextPage('task-1', (taskId, q) => fetchMonitorTaskRuns(taskId, q).then((r) => r.data))
  assert.equal(secondPage, null, 'snapshot change must not return a merged page')
  assert.equal(state.cursor.value, null, 'old cursor must be dropped')
  assert.equal(state.snapshotChanged.value, true, 'old page must be discarded, not mixed')
  assert.deepEqual(state.window.value, keptWindow, 'user window must survive the snapshot reset')
  assert.ok(state.error.value?.includes('监控数据已更新'))

  // 15. Zero illegal interfaces: only the eight whitelisted endpoints plus CSRF appear.
  const allowedPrefixes = [
    '/admin/monitor/credentials', '/admin/monitor/tasks',
    '/api/monitor/tasks',
    '/api/csrf',
  ]
  for (const call of calls) {
    assert.ok(
      allowedPrefixes.some((prefix) => call.path.startsWith(prefix)),
      `illegal endpoint called: ${call.method} ${call.path}`,
    )
    assert.ok(!call.path.includes('/execute') && !call.path.includes('delete'), 'no execute/delete endpoints')
    if (call.method === 'POST' || call.method === 'PATCH') {
      assert.equal(call.credentials, 'include', 'writes carry the session cookie')
      assert.ok(call.headers?.['X-XSRF-TOKEN'], 'writes carry the CSRF header')
    } else {
      assert.equal(call.method, 'GET', 'reads stay pure queries')
    }
  }

  console.log(`monitorApi self-test OK — ${calls.length} requests asserted`)
}

main()
  .catch((error) => {
    console.error('SELF-TEST FAILED:', error)
    process.exitCode = 1
  })
  .finally(() => {
    globalThis.fetch = realFetch
  })
