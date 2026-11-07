<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  fetchMonitorTaskCards,
  fetchMonitorTaskRuns,
  isSnapshotChangedError,
  normalizeMonitorError,
} from '@/services/monitorApi'
import { useMonitorQueryState } from '@/composables/useMonitorQueryState'
import type {
  MonitorRun,
  MonitorTaskCard,
  MonitorView,
} from '@/types/monitor'
import {
  monitorCardAgeLine,
  monitorHistoryView,
  resolveMonitorCardSummary,
  type MonitorCardSummaryView,
} from '@/utils/monitorRecordsPageLogic'
import { formatMonitorUtcTime } from '@/utils/monitorDisplay'

const route = useRoute()
const router = useRouter()

const cards = ref<MonitorTaskCard[]>([])
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const snapshotChanged = ref(false)
const message = ref('')

const windowMode = ref<'24h' | 'custom'>('24h')
const customStart = ref('')
const customEnd = ref('')
const windowError = ref('')
const appliedWindow = ref<{ startIso: string; endIso: string } | null>(null)

const historyTaskId = ref<string | null>(null)
const historyRuns = ref<MonitorRun[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const historyView = ref<MonitorView>('verification')
const historyCursor = ref<string | null>(null)
const historyMeta = ref<{ revision: number; asOf: number } | null>(null)
const historyPage = ref<ReturnType<typeof monitorHistoryView> | null>(null)

const queryState = useMonitorQueryState()
const nowMs = ref(Date.now())
let clockTimer: number | undefined
let loadGeneration = 0

const defaultWindow = computed(() => ({
  startIso: new Date(nowMs.value - 24 * 60 * 60 * 1000).toISOString(),
  endIso: new Date(nowMs.value).toISOString(),
}))

const summaryByTask = computed<Record<string, MonitorCardSummaryView>>(() => {
  const map: Record<string, MonitorCardSummaryView> = {}
  for (const card of cards.value) {
    map[card.task_id] = resolveMonitorCardSummary(card, nowMs.value)
  }
  return map
})

function queryWindow(): { start?: string; end?: string } {
  if (windowMode.value === 'custom' && appliedWindow.value) {
    return { start: appliedWindow.value.startIso, end: appliedWindow.value.endIso }
  }
  return {}
}

async function loadCards() {
  const generation = ++loadGeneration
  refreshing.value = true
  loadError.value = ''
  snapshotChanged.value = false
  try {
    const response = await fetchMonitorTaskCards(queryWindow())
    if (generation !== loadGeneration) return
    cards.value = response.data.tasks
    message.value = ''
  } catch (error: unknown) {
    if (generation !== loadGeneration) return
    if (isSnapshotChangedError(error)) {
      snapshotChanged.value = true
      loadError.value = ''
    } else {
      loadError.value = normalizeMonitorError(error).message
    }
  } finally {
    if (generation === loadGeneration) refreshing.value = false
    loading.value = false
  }
}

function selectMode(mode: '24h' | 'custom') {
  windowMode.value = mode
  windowError.value = ''
  if (mode === '24h') {
    appliedWindow.value = null
    nowMs.value = Date.now()
    queryState.setDefaultWindow(nowMs.value)
    void loadCards()
  }
}

function applyCustomWindow() {
  windowError.value = ''
  const startMs = Date.parse(customStart.value)
  const endMs = Date.parse(customEnd.value)
  if (!customStart.value || !customEnd.value) {
    windowError.value = '请选择开始和结束时间'
    return
  }
  if (Number.isNaN(startMs) || Number.isNaN(endMs) || startMs >= endMs) {
    windowError.value = '时间窗口无效：开始必须早于结束'
    return
  }
  const startIso = new Date(startMs).toISOString()
  const endIso = new Date(endMs).toISOString()
  if (!queryState.setCustomWindow({ startIso, endIso })) {
    windowError.value = '时间窗口无效：开始必须早于结束'
    return
  }
  appliedWindow.value = { startIso, endIso }
  void loadCards()
}

function openHistory(card: MonitorTaskCard) {
  historyTaskId.value = card.task_id
  historyView.value = 'verification'
  historyCursor.value = null
  historyRuns.value = []
  historyError.value = ''
  historyMeta.value = null
  historyPage.value = null
  const bounds = windowMode.value === 'custom' && appliedWindow.value
    ? appliedWindow.value
    : defaultWindow.value
  queryState.setCustomWindow(bounds)
  void loadHistory()
}

function closeHistory() {
  historyTaskId.value = null
  historyRuns.value = []
  historyError.value = ''
  historyPage.value = null
}

function switchHistoryView(view: MonitorView) {
  historyView.value = view
  historyCursor.value = null
  historyRuns.value = []
  historyPage.value = null
  void loadHistory()
}

function historyQuery(cursor?: string) {
  const bounds = queryState.window.value as { startIso: string; endIso: string } | null
  return {
    start: bounds?.startIso,
    end: bounds?.endIso,
    view: historyView.value,
    ...(cursor ? { cursor } : {}),
  }
}

async function loadHistory() {
  if (!historyTaskId.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const response = await fetchMonitorTaskRuns(historyTaskId.value, historyQuery())
    historyRuns.value = response.data.runs
    historyCursor.value = response.data.next_cursor
    historyMeta.value = { revision: response.data.data_revision, asOf: response.data.as_of }
    historyPage.value = monitorHistoryView(response.data, historyView.value)
  } catch (error: unknown) {
    if (isSnapshotChangedError(error)) {
      // Whole-page refresh contract: drop cursor/page, keep the user window, ask to refresh.
      historyCursor.value = null
      historyRuns.value = []
      historyPage.value = null
      historyError.value = '监控数据已更新，请刷新'
    } else {
      historyError.value = normalizeMonitorError(error).message
    }
  } finally {
    historyLoading.value = false
  }
}

async function loadHistoryNextPage() {
  if (!historyTaskId.value || !historyCursor.value || historyLoading.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const response = await fetchMonitorTaskRuns(historyTaskId.value, historyQuery(historyCursor.value))
    const existingEvents = new Set(historyRuns.value.map((run) => `${run.event_at}:${run.started_at ?? ''}:${run.state}`))
    const fresh = response.data.runs.filter((run) => !existingEvents.has(`${run.event_at}:${run.started_at ?? ''}:${run.state}`))
    historyRuns.value = [...historyRuns.value, ...fresh]
    historyCursor.value = response.data.next_cursor
    historyMeta.value = { revision: response.data.data_revision, asOf: response.data.as_of }
    historyPage.value = monitorHistoryView({ ...response.data, runs: historyRuns.value }, historyView.value)
  } catch (error: unknown) {
    if (isSnapshotChangedError(error)) {
      historyCursor.value = null
      historyRuns.value = []
      historyPage.value = null
      historyError.value = '监控数据已更新，请刷新'
    } else {
      historyError.value = normalizeMonitorError(error).message
    }
  } finally {
    historyLoading.value = false
  }
}

function refreshHistory() {
  historyCursor.value = null
  historyRuns.value = []
  historyPage.value = null
  void loadHistory()
}

function cardState(card: MonitorTaskCard): { label: string; kind: string } {
  if (card.blocked_reason) return { label: '配置异常', kind: 'blocked' }
  return card.enabled ? { label: '已启用', kind: 'enabled' } : { label: '已停用', kind: 'disabled' }
}

function openCardHistoryFromRoute() {
  const taskId = typeof route.query.task === 'string' ? route.query.task : null
  if (!taskId) return
  const card = cards.value.find((item) => item.task_id === taskId)
  if (card) openHistory(card)
}

onMounted(() => {
  nowMs.value = Date.now()
  queryState.setDefaultWindow(nowMs.value)
  void loadCards().then(() => openCardHistoryFromRoute())
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})

function navigateToHistory(card: MonitorTaskCard) {
  const bounds = windowMode.value === 'custom' && appliedWindow.value ? appliedWindow.value : defaultWindow.value
  void router.push({
    path: '/monitor-records',
    query: {
      task: card.task_id,
      start: bounds.startIso,
      end: bounds.endIso,
      as_of: String(card.as_of),
    },
  })
  openHistory(card)
}
</script>

<template>
  <section class="monitor-records-page">
    <header class="page-heading">
      <div class="heading-copy">
        <div class="page-title-line">
          <span class="page-eyebrow">MONITOR / RECORDS</span>
          <h1>监控记录</h1>
        </div>
        <p>
          <span>{{ cards.length }} 个任务（含停用）</span>
          <span class="dot">·</span>
          <span>数据按窗口快照展示，显示时间与年龄，不代表实时健康状态</span>
        </p>
      </div>
      <button type="button" class="refresh-action" :disabled="refreshing" @click="loadCards()">
        <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /></svg>
        手动刷新
      </button>
    </header>

    <section class="monitor-window-bar" aria-label="统计窗口">
      <div class="monitor-window-modes" role="group" aria-label="窗口模式">
        <button type="button" class="monitor-tab" :class="{ active: windowMode === '24h' }" :aria-pressed="windowMode === '24h'" @click="selectMode('24h')">最近 24 小时</button>
        <button type="button" class="monitor-tab" :class="{ active: windowMode === 'custom' }" :aria-pressed="windowMode === 'custom'" @click="selectMode('custom')">自定义窗口</button>
      </div>
      <form v-if="windowMode === 'custom'" class="monitor-custom-form" @submit.prevent="applyCustomWindow">
        <label class="monitor-custom-field">
          <span>开始（UTC）</span>
          <input v-model="customStart" type="datetime-local" :aria-invalid="Boolean(windowError)" />
        </label>
        <label class="monitor-custom-field">
          <span>结束（UTC）</span>
          <input v-model="customEnd" type="datetime-local" :aria-invalid="Boolean(windowError)" />
        </label>
        <button type="submit" class="primary-action" :disabled="refreshing">应用窗口</button>
        <span v-if="windowError" class="monitor-field-error" role="alert">{{ windowError }}</span>
        <span v-else-if="appliedWindow" class="field-hint">
          已应用：{{ formatMonitorUtcTime(Date.parse(appliedWindow.startIso)) }} — {{ formatMonitorUtcTime(Date.parse(appliedWindow.endIso)) }}
        </span>
      </form>
      <p v-else class="field-hint">默认窗口为截至当前快照的最近 24 小时；卡片与历史使用同一窗口。</p>
    </section>

    <div v-if="snapshotChanged" class="monitor-snapshot-banner" role="alert">
      <p>监控数据已更新，请刷新。为避免混合新旧数据，已清空旧分页；当前窗口保持不变。</p>
      <button type="button" class="primary-action" @click="loadCards()">刷新数据</button>
    </div>
    <p v-else-if="message" class="monitor-inline-note" role="status">{{ message }}</p>

    <div v-if="loading" class="skeleton-grid" aria-hidden="true">
      <div v-for="item in 4" :key="item" class="quota-skeleton"></div>
    </div>
    <div v-else-if="loadError" class="monitor-error-block" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="primary-action" :disabled="refreshing" @click="loadCards()">重试</button>
    </div>
    <div v-else-if="cards.length === 0" class="empty-state">
      <h2>当前没有监控任务</h2>
      <p>管理员创建监控任务后，这里会展示每个任务的窗口统计。</p>
    </div>

    <div v-else class="monitor-card-grid">
      <article v-for="card in cards" :key="card.task_id" class="quota-card monitor-card">
        <header class="card-head">
          <div class="head-copy">
            <strong :title="card.safe_name">{{ card.safe_name }}</strong>
            <span class="account">{{ card.safe_model_display }}</span>
          </div>
          <span class="monitor-state-badge" :class="cardState(card).kind">{{ cardState(card).label }}</span>
        </header>

        <div class="monitor-conclusion">
          <strong>{{ summaryByTask[card.task_id]?.conclusion ?? '—' }}</strong>
          <span v-if="summaryByTask[card.task_id]?.conclusionDetail" class="monitor-sub-line">{{ summaryByTask[card.task_id]?.conclusionDetail }}</span>
        </div>

        <div class="monitor-stat-row">
          <div class="stat-item">
            <span>窗口成功率</span>
            <strong>{{ summaryByTask[card.task_id]?.ratioText ?? '—' }}</strong>
            <span class="monitor-sub-line">{{ summaryByTask[card.task_id]?.ratioNote ?? '' }}</span>
          </div>
          <div class="stat-item">
            <span>样本 / 成功 / 失败 / 不确定</span>
            <strong>{{ card.verification.sample_count }} / {{ card.verification.success_count }} / {{ card.verification.failed_count }} / {{ card.verification.unknown_count }}</strong>
          </div>
        </div>

        <div class="monitor-latest-grid">
          <div class="monitor-latest-cell">
            <span>上次验证</span>
            <strong>{{ summaryByTask[card.task_id]?.latestVerificationLine ?? '暂无记录' }}</strong>
          </div>
          <div class="monitor-latest-cell">
            <span>最近调度事件</span>
            <strong>{{ summaryByTask[card.task_id]?.latestEventLine ?? '暂无记录' }}</strong>
          </div>
        </div>

        <div class="monitor-coverage-note">
          <span v-if="summaryByTask[card.task_id]?.coverageNote" class="monitor-sub-line">{{ summaryByTask[card.task_id]?.coverageNote }}</span>
          <span v-else class="monitor-sub-line">窗口数据完整</span>
          <span class="monitor-sub-line">{{ monitorCardAgeLine(card, nowMs) }} · 截至 {{ formatMonitorUtcTime(card.as_of) }}</span>
          <span v-if="card.next_fire_at" class="monitor-sub-line">下次调度 {{ formatMonitorUtcTime(card.next_fire_at) }}</span>
        </div>

        <footer class="monitor-card-footer">
          <button type="button" class="table-action edit monitor-history-link" @click="navigateToHistory(card)">查看历史</button>
        </footer>
      </article>
    </div>

    <Transition name="drawer">
      <div v-if="historyTaskId" class="drawer-layer" @click.self="closeHistory">
        <aside class="edit-drawer monitor-history-drawer" aria-label="监控历史">
          <header class="drawer-header">
            <div>
              <span class="page-eyebrow">MONITOR / HISTORY</span>
              <h2>验证与调度历史</h2>
            </div>
            <button type="button" class="drawer-close" aria-label="关闭" @click="closeHistory">×</button>
          </header>
          <div class="drawer-content">
            <div class="monitor-history-toolbar">
              <div class="monitor-history-tabs" role="group" aria-label="历史视图">
                <button type="button" class="monitor-tab" :class="{ active: historyView === 'verification' }" :aria-pressed="historyView === 'verification'" @click="switchHistoryView('verification')">验证结果</button>
                <button type="button" class="monitor-tab" :class="{ active: historyView === 'events' }" :aria-pressed="historyView === 'events'" @click="switchHistoryView('events')">调度事件</button>
              </div>
              <span v-if="historyMeta" class="field-hint">数据版本 {{ historyMeta.revision }} · 截至 {{ formatMonitorUtcTime(historyMeta.asOf) }}</span>
            </div>
            <p v-if="historyError" class="form-error" role="alert">
              {{ historyError }}
              <button type="button" class="table-action edit" @click="refreshHistory">刷新</button>
            </p>
            <div v-if="historyLoading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 3" :key="item"></span></div>
            <div v-else-if="historyRuns.length === 0 && !historyError" class="admin-empty-state">
              <h2>{{ historyPage?.emptyCopy || '该窗口内暂无验证记录' }}</h2>
              <p>验证视图只显示计入样本的验证结果；调度事件视图显示跳过等调度记录。</p>
            </div>
            <ul v-else class="monitor-history-list">
              <li v-for="(run, index) in historyRuns" :key="`${run.event_at}-${index}`" class="monitor-history-item">
                <div class="monitor-history-head">
                  <span class="monitor-state-badge" :class="run.state === 'SUCCESS' ? 'enabled' : run.state === 'FAILED' ? 'blocked' : 'disabled'">
                    {{ run.state === 'SUCCESS' ? '成功' : run.state === 'FAILED' ? '失败' : run.state === 'PENDING' ? '执行中' : run.state === 'SKIPPED' ? '已跳过' : run.state === 'UNKNOWN' ? '结果不确定' : '配置异常' }}
                  </span>
                  <span class="monitor-sub-line">{{ run.dispatch_status === 'MAY_HAVE_SENT' ? '可能已发送' : '未发送' }}</span>
                  <span v-if="historyView === 'verification'" class="monitor-sub-line">{{ run.included_in_sample ? '计入样本' : '不计入样本' }}</span>
                  <span v-else class="monitor-sub-line">{{ run.included_in_sample ? '计入样本' : '调度记录' }}</span>
                </div>
                <p class="monitor-sub-line">开始 {{ formatMonitorUtcTime(run.started_at) }} · 结束 {{ formatMonitorUtcTime(run.finished_at) }}</p>
                <p class="monitor-sub-line">记录时间 {{ formatMonitorUtcTime(run.event_at) }}</p>
              </li>
            </ul>
            <footer v-if="historyCursor" class="monitor-history-footer">
              <button type="button" class="primary-action" :disabled="historyLoading" @click="loadHistoryNextPage">
                {{ historyLoading ? '加载中…' : '加载下一页' }}
              </button>
            </footer>
          </div>
        </aside>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.monitor-records-page .monitor-window-bar {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  background: color-mix(in srgb, var(--bg-primary) 70%, transparent);
}

.monitor-window-modes {
  display: inline-flex;
  gap: 6px;
}

.monitor-tab {
  padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--border-color) 85%, transparent);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.monitor-tab.active {
  background: var(--text-primary);
  border-color: transparent;
  color: var(--bg-secondary);
}

.monitor-custom-form {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
}

.monitor-custom-field {
  display: grid;
  gap: 6px;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 700;
}

.monitor-custom-field input {
  min-height: 38px;
  min-width: 0;
  padding: 0 10px;
  border: 1px solid var(--border-primary);
  border-radius: 9px;
  background: color-mix(in srgb, var(--bg-secondary) 76%, transparent);
  color: var(--text-primary);
  font: inherit;
  font-size: 12px;
}

.monitor-field-error {
  color: #a13d2c;
  font-size: 11px;
}

.monitor-snapshot-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid rgba(198, 87, 70, 0.3);
  border-radius: 12px;
  background: rgba(198, 87, 70, 0.08);
  color: #8a3a30;
  font-size: 12px;
}

.monitor-inline-note {
  margin: -8px 0 14px;
  padding: 9px 12px;
  border: 1px solid rgba(16, 185, 129, 0.28);
  border-radius: 10px;
  background: rgba(16, 185, 129, 0.08);
  color: #065f46;
  font-size: 12px;
}

.monitor-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 340px), 1fr));
  gap: 14px;
}

.monitor-card {
  display: grid;
  gap: 12px;
}

.monitor-conclusion strong {
  font-size: 16px;
  letter-spacing: -0.01em;
}

.monitor-stat-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.monitor-stat-row .stat-item strong {
  font-size: 15px;
}

.monitor-latest-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--border-color) 78%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--bg-secondary) 55%, transparent);
}

.monitor-latest-cell {
  display: grid;
  gap: 4px;
  color: var(--text-tertiary);
  font-size: 11px;
}

.monitor-latest-cell strong {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 650;
  overflow-wrap: anywhere;
}

.monitor-coverage-note {
  display: grid;
  gap: 2px;
}

.monitor-card-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
  border-top: 1px solid color-mix(in srgb, var(--border-color) 70%, transparent);
}

.monitor-history-link {
  height: 32px;
  padding: 0 14px;
  border: 1px solid color-mix(in srgb, var(--border-color) 85%, transparent);
  border-radius: 999px;
}

.monitor-history-drawer {
  width: min(620px, 100vw);
}

.monitor-history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.monitor-history-tabs {
  display: inline-flex;
  gap: 6px;
}

.monitor-history-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.monitor-history-item {
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--border-color) 80%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--bg-secondary) 62%, transparent);
}

.monitor-history-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.monitor-history-footer {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}

@media (max-width: 700px) {
  .monitor-custom-form {
    align-items: stretch;
    flex-direction: column;
  }

  .monitor-snapshot-banner {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (max-width: 480px) {
  .monitor-stat-row,
  .monitor-latest-grid {
    grid-template-columns: 1fr;
  }

  .monitor-window-modes {
    width: 100%;
  }

  .monitor-window-modes .monitor-tab {
    flex: 1;
  }

  .monitor-history-tabs {
    width: 100%;
  }

  .monitor-history-tabs .monitor-tab {
    flex: 1;
  }
}
</style>
