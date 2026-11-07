import { computed, readonly, ref } from 'vue'
import type {
  AdminMonitorRunPageResponse,
  MonitorCardsQuery,
  MonitorHistoryQuery,
  MonitorRunPageResponse,
  MonitorView,
} from '@/types/monitor'
import { isSnapshotChangedError, normalizeMonitorError } from '@/services/monitorApi'
import { toMonitorIsoInstant } from '@/utils/monitorDisplay'

/**
 * Shared monitoring query state (T12 / D6.3). One captured window/as_of/view/cursor
 * revision stays page-consistent. SNAPSHOT_CHANGED (409) discards the old cursor and page
 * while preserving the user's chosen window, asking the caller to refresh from page one —
 * old and new data are never merged.
 */

export interface MonitorWindowBounds {
  startIso: string
  endIso: string
}

export interface MonitorQuerySnapshot {
  taskId: string
  startIso: string
  endIso: string
  asOfMs: number
  view: MonitorView
  cursor: string | null
  dataRevision: number
  isSnapshotChanged: boolean
}

export interface MonitorRunPage<T> {
  runs: T[]
  nextCursor: string | null
  snapshot: MonitorQuerySnapshot
}

export interface MonitorPageMeta {
  data_revision: number
  as_of: number
  next_cursor: string | null
}

function clampLimit(limit?: number): number | undefined {
  if (limit === undefined) return undefined
  return Math.min(Math.max(1, Math.floor(limit)), 100)
}

export function useMonitorQueryState() {
  const window = ref<MonitorWindowBounds | null>(null)
  const view = ref<MonitorView>('events')
  const cursor = ref<string | null>(null)
  const dataRevision = ref<number | null>(null)
  const asOfMs = ref<number | null>(null)
  const snapshotChanged = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const runs = ref<unknown[]>([])

  const isCustomWindow = computed(() => window.value !== null)
  const query = computed<MonitorHistoryQuery>(() => ({
    ...(window.value ?? {}),
    view: view.value,
    cursor: cursor.value ?? undefined,
  }))

  function preserveWindow(bounds: MonitorWindowBounds) {
    window.value = { ...bounds }
  }

  /** Sets a custom absolute window; invalid inputs are rejected before any fetch. */
  function setCustomWindow(bounds: MonitorWindowBounds): boolean {
    const startMs = Date.parse(bounds.startIso)
    const endMs = Date.parse(bounds.endIso)
    if (Number.isNaN(startMs) || Number.isNaN(endMs) || startMs >= endMs) return false
    preserveWindow(bounds)
    resetPaging()
    return true
  }

  function setDefaultWindow(nowMs: number) {
    preserveWindow({ startIso: toMonitorIsoInstant(nowMs - 24 * 60 * 60 * 1000), endIso: toMonitorIsoInstant(nowMs) })
    resetPaging()
  }

  function setView(nextView: MonitorView) {
    if (view.value === nextView) return
    view.value = nextView
    resetPaging()
  }

  /** SNAPSHOT_CHANGED contract: drop cursor/page, keep the user window, require refresh. */
  function resetPaging() {
    cursor.value = null
    runs.value = []
    snapshotChanged.value = false
    error.value = null
  }

  function markSnapshotChanged() {
    snapshotChanged.value = true
    cursor.value = null
    runs.value = []
    error.value = '监控数据已更新，请刷新'
  }

  function trackPage(taskId: string, page: MonitorPageMeta) {
    dataRevision.value = page.data_revision
    asOfMs.value = page.as_of
    cursor.value = page.next_cursor
    snapshotChanged.value = false
    return {
      taskId,
      startIso: window.value?.startIso ?? '',
      endIso: window.value?.endIso ?? '',
      asOfMs: page.as_of,
      view: view.value,
      cursor: page.next_cursor,
      dataRevision: page.data_revision,
      isSnapshotChanged: false,
    } satisfies MonitorQuerySnapshot
  }

  async function loadPublicPage(
    taskId: string,
    fetchPage: (taskId: string, query: MonitorHistoryQuery) => Promise<MonitorRunPageResponse>,
  ): Promise<MonitorRunPage<MonitorRunPageResponse['runs'][number]>> {
    loading.value = true
    error.value = null
    try {
      const page = await fetchPage(taskId, query.value)
      const snapshot = trackPage(taskId, page)
      runs.value = page.runs
      return { runs: page.runs, nextCursor: page.next_cursor, snapshot }
    } catch (caught) {
      if (isSnapshotChangedError(caught)) markSnapshotChanged()
      else error.value = normalizeMonitorError(caught).message
      throw caught
    } finally {
      loading.value = false
    }
  }

  async function loadAdminPage(
    taskId: string,
    fetchPage: (taskId: string, query: MonitorHistoryQuery) => Promise<AdminMonitorRunPageResponse>,
  ): Promise<MonitorRunPage<AdminMonitorRunPageResponse['runs'][number]>> {
    loading.value = true
    error.value = null
    try {
      const page = await fetchPage(taskId, query.value)
      const snapshot = trackPage(taskId, page)
      runs.value = page.runs
      return { runs: page.runs, nextCursor: page.next_cursor, snapshot }
    } catch (caught) {
      if (isSnapshotChangedError(caught)) markSnapshotChanged()
      else error.value = normalizeMonitorError(caught).message
      throw caught
    } finally {
      loading.value = false
    }
  }

  /** Load one more page using the retained signed cursor; 409 clears it via markSnapshotChanged. */
  async function loadNextPage<T>(
    taskId: string,
    fetchPage: (
      taskId: string,
      query: MonitorHistoryQuery,
    ) => Promise<MonitorPageMeta & { runs: T[]; next_cursor: string | null }>,
  ): Promise<MonitorRunPage<T> | null> {
    if (!cursor.value || loading.value) return null
    loading.value = true
    error.value = null
    try {
      const page = await fetchPage(taskId, { ...query.value, cursor: cursor.value, limit: clampLimit() })
      const snapshot = trackPage(taskId, page)
      const combined = [...(runs.value as T[]), ...page.runs]
      runs.value = combined
      return { runs: combined, nextCursor: page.next_cursor, snapshot }
    } catch (caught) {
      if (isSnapshotChangedError(caught)) markSnapshotChanged()
      else error.value = normalizeMonitorError(caught).message
      return null
    } finally {
      loading.value = false
    }
  }

  /** Builds card-window parameters that stay consistent with the captured history window. */
  function buildCardsQuery(): MonitorCardsQuery {
    return window.value ? { start: window.value.startIso, end: window.value.endIso } : {}
  }

  return {
    window: readonly(window),
    view: readonly(view),
    cursor: readonly(cursor),
    dataRevision: readonly(dataRevision),
    asOfMs: readonly(asOfMs),
    snapshotChanged: readonly(snapshotChanged),
    loading: readonly(loading),
    error: readonly(error),
    isCustomWindow,
    query,
    setCustomWindow,
    setDefaultWindow,
    setView,
    resetPaging,
    markSnapshotChanged,
    loadPublicPage,
    loadAdminPage,
    loadNextPage,
    buildCardsQuery,
  }
}
