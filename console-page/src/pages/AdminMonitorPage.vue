<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchAdminMonitorCandidates,
  fetchAdminMonitorCandidateModels,
  fetchAdminMonitorTaskRuns,
  fetchAdminMonitorTasks,
  fetchCreateAdminMonitorTask,
  fetchUpdateAdminMonitorTask,
  isRevisionConflictError,
  normalizeMonitorError,
} from '@/services/monitorApi'
import { useMonitorQueryState } from '@/composables/useMonitorQueryState'
import type {
  AdminMonitorCandidate,
  AdminMonitorRun,
  AdminMonitorTask,
  MonitorHistoryQuery,
} from '@/types/monitor'
import {
  ADMIN_EDITABLE_FIELDS,
  adminRunDisplaySummary,
  adminRunPhaseLine,
  adminRevisionState,
  adminTaskStateView,
  buildAdminPlanDraft,
  describeAdminPlan,
  formatAdminCandidateExpiry,
  formatAdminRunSummaryLine,
  isAdminCronShapeValid,
  resolveAdminBlockedCopy,
} from '@/utils/adminMonitorPageLogic'
import { formatMonitorUtcTime } from '@/utils/monitorDisplay'

const tasks = ref<AdminMonitorTask[]>([])
const candidates = ref<AdminMonitorCandidate[]>([])
const candidatesLoadedAt = ref<number | null>(null)
const candidateChoices = ref<Record<string, Record<string, string>>>({})
const candidateModels = ref<string[]>([])
const currentCandidateModels = ref<Record<string, string>>({})

const loading = ref(true)
const refreshing = ref(false)
const candidatesLoading = ref(false)
const modelsLoading = ref(false)
const listError = ref('')
const message = ref('')
const formError = ref('')
const saving = ref(false)

const createOpen = ref(false)
const createSafeName = ref('')
const createCron = ref('0 0 3 * * *')
const createZone = ref('UTC')
const createCandidateId = ref('')
const createModelChoiceId = ref('')

const editingTaskId = ref<string | null>(null)
const editSafeName = ref('')
const editCron = ref('')
const editZone = ref('')
const editEnabled = ref(false)
const editingRowVersion = ref(0)

const historyTaskId = ref<string | null>(null)
const historyRuns = ref<AdminMonitorRun[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const historyView = ref<'verification' | 'events'>('events')
const historyCursor = ref<string | null>(null)
const historyRevision = ref<number | null>(null)
const historyAsOf = ref<number | null>(null)

const queryState = useMonitorQueryState()
const nowMs = ref(Date.now())
let revisionTimer: number | undefined
let revisionPending = false

const selectedCandidate = computed(() =>
  candidates.value.find((item) => item.candidate_id === createCandidateId.value) ?? null,
)

const candidateExpiryCopy = computed(() => {
  if (!createOpen.value || !createCandidateId.value) return ''
  const stamp = candidatesLoadedAt.value
  if (stamp === null) return ''
  return formatAdminCandidateExpiry(stamp, nowMs.value)
})

const createModelChoices = computed(() =>
  createCandidateId.value ? candidateChoices.value[createCandidateId.value] ?? {} : {},
)

const createPlanHelp = computed(() =>
  describeAdminPlan(createCron.value, createZone.value, null),
)

const editPlanHelp = computed(() => {
  const task = tasks.value.find((item) => item.task_id === editingTaskId.value)
  return describeAdminPlan(editCron.value, editZone.value, task?.next_fire_at ?? null)
})

const editingTask = computed(() =>
  tasks.value.find((item) => item.task_id === editingTaskId.value) ?? null,
)

function taskView(task: AdminMonitorTask) {
  return adminTaskStateView(task, nowMs.value)
}

function refreshCandidates() {
  candidatesLoading.value = true
  formError.value = ''
  return fetchAdminMonitorCandidates()
    .then((response) => {
      candidates.value = response.data.candidates
      candidateChoices.value = response.data.model_choices
      candidatesLoadedAt.value = Date.now()
      nowMs.value = Date.now()
      if (!candidates.value.some((item) => item.candidate_id === createCandidateId.value)) {
        createCandidateId.value = ''
        createModelChoiceId.value = ''
        candidateModels.value = []
      }
    })
    .catch((error: unknown) => {
      formError.value = normalizeMonitorError(error).message
    })
    .finally(() => {
      candidatesLoading.value = false
    })
}

function loadCandidateModels(candidateId: string) {
  createCandidateId.value = candidateId
  createModelChoiceId.value = ''
  candidateModels.value = []
  if (!candidateId) {
    currentCandidateModels.value = {}
    return
  }
  modelsLoading.value = true
  formError.value = ''
  fetchAdminMonitorCandidateModels(candidateId)
    .then((response) => {
      candidateModels.value = response.data.models
      currentCandidateModels.value = response.data.model_choices
      if (Object.keys(response.data.model_choices).length > 0) {
        createModelChoiceId.value = Object.keys(response.data.model_choices)[0] ?? ''
      }
    })
    .catch((error: unknown) => {
      const monitorError = normalizeMonitorError(error)
      formError.value = monitorError.message
      if (monitorError.code === 'CANDIDATE_NOT_FOUND') {
        createCandidateId.value = ''
        createModelChoiceId.value = ''
        candidateModels.value = []
        void refreshCandidates()
      }
    })
    .finally(() => {
      modelsLoading.value = false
    })
}

function loadTasks() {
  refreshing.value = true
  listError.value = ''
  return fetchAdminMonitorTasks()
    .then((response) => {
      tasks.value = response.data.tasks
      const stillEditing = editingTaskId.value
        ? response.data.tasks.find((task) => task.task_id === editingTaskId.value)
        : null
      if (editingTaskId.value && stillEditing) {
        editingRowVersion.value = stillEditing.row_version
      } else if (editingTaskId.value && !stillEditing) {
        closeEdit()
        message.value = '任务已被移除，列表已刷新'
      }
    })
    .catch((error: unknown) => {
      listError.value = normalizeMonitorError(error).message
    })
    .finally(() => {
      loading.value = false
      refreshing.value = false
    })
}

function refreshAll() {
  return Promise.all([loadTasks(), refreshCandidates()])
}

function openCreate() {
  createOpen.value = true
  formError.value = ''
  message.value = ''
  createSafeName.value = ''
  createCron.value = '0 0 3 * * *'
  createZone.value = 'UTC'
  createCandidateId.value = ''
  createModelChoiceId.value = ''
  candidateModels.value = []
  nowMs.value = Date.now()
  void refreshCandidates()
}

function closeCreate() {
  if (saving.value) return
  createOpen.value = false
  formError.value = ''
}

async function submitCreate() {
  formError.value = ''
  if (!createSafeName.value.trim()) {
    formError.value = '请填写安全名称'
    return
  }
  if (!isAdminCronShapeValid(createCron.value)) {
    formError.value = 'cron 表达式必须为六段（秒 分 时 日 月 周）'
    return
  }
  if (!createCandidateId.value || !createModelChoiceId.value) {
    formError.value = '请选择受支持的候选与模型'
    return
  }
  if (candidateExpiryCopy.value) {
    formError.value = candidateExpiryCopy.value
    return
  }
  saving.value = true
  try {
    const payload = {
      safe_name: createSafeName.value.trim(),
      ...buildAdminPlanDraft(createCron.value, createZone.value),
      instance_key: createCandidateId.value,
      model_choice_id: createModelChoiceId.value,
    }
    await fetchCreateAdminMonitorTask(payload)
    createOpen.value = false
    message.value = '任务已创建，默认停用；请先校验后启用'
    await loadTasks()
  } catch (error: unknown) {
    const monitorError = normalizeMonitorError(error)
    formError.value = monitorError.code === 'CANDIDATE_EXPIRED' || monitorError.code === 'CANDIDATE_NOT_FOUND'
      ? '所选候选已过期，请重新获取候选列表后再选择'
      : monitorError.message
  } finally {
    saving.value = false
  }
}

function openEdit(task: AdminMonitorTask) {
  editingTaskId.value = task.task_id
  editingRowVersion.value = task.row_version
  editSafeName.value = task.safe_name
  editCron.value = task.cron_expression
  editZone.value = task.zone_id
  editEnabled.value = task.enabled
  formError.value = ''
  message.value = ''
}

function closeEdit() {
  editingTaskId.value = null
  formError.value = ''
}

async function submitEdit() {
  if (!editingTaskId.value || !editingTask.value) return
  formError.value = ''
  if (!editSafeName.value.trim()) {
    formError.value = '请填写安全名称'
    return
  }
  if (!isAdminCronShapeValid(editCron.value)) {
    formError.value = 'cron 表达式必须为六段（秒 分 时 日 月 周）'
    return
  }
  saving.value = true
  try {
    const response = await fetchUpdateAdminMonitorTask(editingTaskId.value, {
      expected_revision: editingTask.value.schedule_revision,
      safe_name: editSafeName.value.trim(),
      cron_expression: editCron.value.trim(),
      zone_id: editZone.value.trim() || 'UTC',
      enabled: editEnabled.value,
    })
    editingRowVersion.value = response.data.row_version
    message.value = editEnabled.value
      ? '任务已保存并重新校验启用'
      : '任务已保存并保持停用'
    await loadTasks()
  } catch (error: unknown) {
    if (isRevisionConflictError(error)) {
      formError.value = adminRevisionState({ code: 'REVISION_CONFLICT' })
      await loadTasks()
    } else {
      formError.value = normalizeMonitorError(error).message
    }
  } finally {
    saving.value = false
  }
}

function openHistory(task: AdminMonitorTask) {
  historyTaskId.value = task.task_id
  historyView.value = 'events'
  historyCursor.value = null
  historyRuns.value = []
  historyError.value = ''
  historyRevision.value = null
  historyAsOf.value = null
  if (!queryState.window.value) {
    queryState.setDefaultWindow(nowMs.value)
  }
  void loadHistory()
}

function closeHistory() {
  historyTaskId.value = null
  historyRuns.value = []
  historyError.value = ''
}

function switchHistoryView(view: 'verification' | 'events') {
  historyView.value = view
  historyCursor.value = null
  historyRuns.value = []
  void loadHistory()
}

function historyQuery(): MonitorHistoryQuery {
  const base = queryState.query.value as MonitorHistoryQuery
  return { ...base, view: historyView.value }
}

async function loadHistory() {
  if (!historyTaskId.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const response = await fetchAdminMonitorTaskRuns(historyTaskId.value, historyQuery())
    historyRuns.value = response.data.runs
    historyCursor.value = response.data.next_cursor
    historyRevision.value = response.data.data_revision
    historyAsOf.value = response.data.as_of
  } catch (error: unknown) {
    const monitorError = normalizeMonitorError(error)
    historyError.value = monitorError.message
    if (monitorError.code === 'SNAPSHOT_CHANGED') {
      historyCursor.value = null
      historyRuns.value = []
    }
  } finally {
    historyLoading.value = false
  }
}

async function loadHistoryNextPage() {
  if (!historyTaskId.value || !historyCursor.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const response = await fetchAdminMonitorTaskRuns(historyTaskId.value, {
      ...historyQuery(),
      cursor: historyCursor.value,
    })
    const nextRuns = response.data.runs
    const existingEvents = new Set(historyRuns.value.map((run) => run.run_id))
    historyRuns.value = [...historyRuns.value, ...nextRuns.filter((run) => !existingEvents.has(run.run_id))]
    historyCursor.value = response.data.next_cursor
    historyRevision.value = response.data.data_revision
    historyAsOf.value = response.data.as_of
  } catch (error: unknown) {
    const monitorError = normalizeMonitorError(error)
    historyError.value = monitorError.message
    if (monitorError.code === 'SNAPSHOT_CHANGED') {
      historyCursor.value = null
      historyRuns.value = []
    }
  } finally {
    historyLoading.value = false
  }
}

onMounted(() => {
  void loadTasks()
  revisionTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onBeforeUnmount(() => {
  if (revisionTimer !== undefined) window.clearInterval(revisionTimer)
})

function summaryLine(status: AdminMonitorRun['dispatch_status']) {
  return formatAdminRunSummaryLine(status)
}

const summaryLineFor = summaryLine
void summaryLineFor
const blockedCopyFor = resolveAdminBlockedCopy
void blockedCopyFor
void ADMIN_EDITABLE_FIELDS
</script>

<template>
  <section class="page admin-list-page monitor-admin-page">
    <header class="admin-page-heading">
      <div>
        <div class="page-title-line">
          <span class="page-eyebrow">ADMIN / MONITOR</span>
          <h1>定时监控</h1>
        </div>
        <p>查看定时监控任务、创建新任务并管理安全编辑与启停。只读历史不包含原始响应。</p>
      </div>
      <div class="admin-heading-actions">
        <button type="button" class="primary-action" @click="openCreate">新建任务</button>
        <button type="button" class="refresh-action" :disabled="refreshing" @click="refreshAll">
          <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /></svg>
          刷新列表
        </button>
      </div>
    </header>

    <p v-if="message" class="monitor-inline-note" role="status">{{ message }}</p>

    <section class="admin-list-card">
      <div class="admin-list-toolbar">
        <div class="admin-list-summary"><strong>{{ tasks.length }}</strong><span>个任务（含停用与阻塞）</span></div>
        <span class="field-hint">目标与模型创建后不可修改；修改需基于最新版本。</span>
      </div>

      <div v-if="loading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 4" :key="item"></span></div>
      <div v-else-if="listError" class="monitor-error-block" role="alert">
        <p>{{ listError }}</p>
        <button type="button" class="primary-action" :disabled="refreshing" @click="loadTasks">重试</button>
      </div>
      <div v-else-if="tasks.length === 0" class="admin-empty-state">
        <h2>当前没有监控任务</h2>
        <p>请先创建任务；新任务默认停用，启用前会重新校验目标。</p>
      </div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table monitor-task-table">
          <thead>
            <tr>
              <th>任务</th>
              <th>状态</th>
              <th>定时计划</th>
              <th>版本</th>
              <th class="operation-column">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.task_id">
              <td>
                <div class="user-name-cell">
                  <strong>{{ task.safe_name }}</strong>
                </div>
                <span class="monitor-sub-line">{{ task.safe_model_display }} · {{ task.provider }}</span>
                <span v-if="taskView(task).blockedCopy" class="monitor-warning-line">{{ taskView(task).blockedCopy }}</span>
              </td>
              <td>
                <span class="monitor-state-badge" :class="taskView(task).stateKind">{{ taskView(task).stateLabel }}</span>
                <span class="monitor-sub-line">最近调度 {{ taskView(task).nextFireText }}</span>
              </td>
              <td>
                <code class="monitor-cron">{{ task.cron_expression }}</code>
                <span class="monitor-sub-line">时区 {{ task.zone_id }} · 下次 {{ formatMonitorUtcTime(task.next_fire_at) }}</span>
                <span class="monitor-sub-line">调度版本 {{ task.schedule_revision }}</span>
              </td>
              <td>
                <span class="user-id">行版本 {{ task.row_version }}</span>
                <span class="monitor-sub-line">更新 {{ formatMonitorUtcTime(task.updated_at) }}</span>
              </td>
              <td class="operation-cell">
                <button type="button" class="table-action edit" @click="openEdit(task)">安全编辑</button>
                <button type="button" class="table-action edit" @click="openHistory(task)">查看历史</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Transition name="drawer">
      <div v-if="createOpen" class="drawer-layer" @click.self="closeCreate">
        <aside class="edit-drawer monitor-edit-drawer" aria-label="新建监控任务">
          <header class="drawer-header">
            <div>
              <span class="page-eyebrow">ADMIN / MONITOR / NEW</span>
              <h2>新建监控任务</h2>
            </div>
            <button type="button" class="drawer-close" aria-label="关闭" @click="closeCreate">×</button>
          </header>
          <form class="drawer-content" @submit.prevent="submitCreate">
            <p class="drawer-note">
              新任务默认停用。目标只来自当前受支持候选，创建后不可替换；候选过期需重新获取，不会按名称恢复。
            </p>
            <div class="drawer-field">
              <label for="monitor-create-name">安全名称</label>
              <input id="monitor-create-name" v-model="createSafeName" type="text" :disabled="saving" required />
            </div>
            <div class="drawer-field">
              <label for="monitor-create-cron">定时计划（六段 cron）</label>
              <input id="monitor-create-cron" v-model="createCron" type="text" :disabled="saving" required />
              <span class="field-hint">{{ createPlanHelp }}</span>
              <span v-if="!isAdminCronShapeValid(createCron)" class="monitor-field-error" aria-live="polite">
                需要六段：秒 分 时 日 月 周；复杂校验由后端完成，未通过前不会保存。
              </span>
            </div>
            <div class="drawer-field">
              <label for="monitor-create-zone">时区（IANA）</label>
              <input id="monitor-create-zone" v-model="createZone" type="text" placeholder="UTC" :disabled="saving" required />
              <span class="field-hint">默认 UTC；仅接受 IANA 时区 ID，不接受数字偏移。</span>
            </div>
            <div class="drawer-field">
              <label for="monitor-create-candidate">候选（实时）</label>
              <select id="monitor-create-candidate" :value="createCandidateId" :disabled="saving || candidatesLoading" @change="loadCandidateModels(($event.target as HTMLSelectElement).value)">
                <option value="">请选择受支持的候选</option>
                <option
                  v-for="candidate in candidates"
                  :key="candidate.candidate_id"
                  :value="candidate.candidate_id"
                  :disabled="!candidate.supported"
                >
                  {{ candidate.provider }} · {{ candidate.sequence }}{{ candidate.supported ? '' : `（不可用：${candidate.unsupported_reason ?? '不支持'}）` }}
                </option>
              </select>
              <span v-if="candidateExpiryCopy" class="monitor-field-error" role="alert">{{ candidateExpiryCopy }}</span>
              <span v-else class="field-hint">候选是短期令牌，过期后必须重新获取，不会按名称恢复。</span>
            </div>
            <div class="drawer-field">
              <label for="monitor-create-model">模型选择</label>
              <select id="monitor-create-model" v-model="createModelChoiceId" :disabled="saving || modelsLoading || !createCandidateId">
                <option value="">请先选择候选</option>
                <option v-for="(model, choiceId) in createModelChoices" :key="choiceId" :value="choiceId">{{ model }}</option>
              </select>
              <span class="field-hint">只列出当前候选支持的模型；目标与模型创建后不可修改。</span>
            </div>
            <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
            <footer class="drawer-footer">
              <button type="button" class="ghost-action" :disabled="saving" @click="closeCreate">取消</button>
              <button type="button" class="ghost-action" :disabled="saving || candidatesLoading" @click="refreshCandidates">刷新候选</button>
              <button type="submit" class="primary-action" :disabled="saving || candidatesLoading || modelsLoading">
                {{ saving ? '创建中…' : '创建（默认停用）' }}
              </button>
            </footer>
          </form>
        </aside>
      </div>
    </Transition>

    <Transition name="drawer">
      <div v-if="editingTask" class="drawer-layer" @click.self="closeEdit">
        <aside class="edit-drawer monitor-edit-drawer" aria-label="安全编辑监控任务">
          <header class="drawer-header">
            <div>
              <span class="page-eyebrow">ADMIN / MONITOR / EDIT</span>
              <h2>安全编辑</h2>
            </div>
            <button type="button" class="drawer-close" aria-label="关闭" @click="closeEdit">×</button>
          </header>
          <form class="drawer-content" @submit.prevent="submitEdit">
            <div class="drawer-readonly">
              <span>监控目标（不可修改）</span>
              <strong>{{ editingTask.safe_model_display }}</strong>
              <code>目标与模型不可替换；如需更换目标请另建任务。</code>
            </div>
            <div class="drawer-field">
              <label for="monitor-edit-name">安全名称</label>
              <input id="monitor-edit-name" v-model="editSafeName" type="text" :disabled="saving" required />
            </div>
            <div class="drawer-field">
              <label for="monitor-edit-cron">定时计划（六段 cron）</label>
              <input id="monitor-edit-cron" v-model="editCron" type="text" :disabled="saving" required />
              <span class="field-hint">{{ editPlanHelp }}</span>
              <span v-if="!isAdminCronShapeValid(editCron)" class="monitor-field-error" aria-live="polite">
                需要六段：秒 分 时 日 月 周；复杂校验由后端完成，未通过前不会保存。
              </span>
            </div>
            <div class="drawer-field">
              <label for="monitor-edit-zone">时区（IANA）</label>
              <input id="monitor-edit-zone" v-model="editZone" type="text" :disabled="saving" required />
              <span class="field-hint">默认 UTC；修改计划会重新校验并更新调度版本。</span>
            </div>
            <label class="monitor-enable-toggle">
              <input v-model="editEnabled" type="checkbox" :disabled="saving" />
              <span>启用任务（启用时后端会重新校验目标）</span>
            </label>
            <div class="drawer-readonly">
              <span>版本</span>
              <strong>调度版本 {{ editingTask.schedule_revision }} · 行版本 {{ editingRowVersion }}</strong>
            </div>
            <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
            <footer class="drawer-footer">
              <button type="button" class="ghost-action" :disabled="saving" @click="closeEdit">取消</button>
              <button type="submit" class="primary-action" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
            </footer>
          </form>
        </aside>
      </div>
    </Transition>

    <Transition name="drawer">
      <div v-if="historyTaskId" class="drawer-layer" @click.self="closeHistory">
        <aside class="edit-drawer monitor-history-drawer" aria-label="监控任务历史">
          <header class="drawer-header">
            <div>
              <span class="page-eyebrow">ADMIN / MONITOR / HISTORY</span>
              <h2>任务历史</h2>
            </div>
            <button type="button" class="drawer-close" aria-label="关闭" @click="closeHistory">×</button>
          </header>
          <div class="drawer-content">
            <div class="monitor-history-toolbar">
              <div class="monitor-history-tabs" role="group" aria-label="历史视图">
                <button
                  type="button"
                  class="monitor-tab"
                  :class="{ active: historyView === 'events' }"
                  :aria-pressed="historyView === 'events'"
                  @click="switchHistoryView('events')"
                >调度事件</button>
                <button
                  type="button"
                  class="monitor-tab"
                  :class="{ active: historyView === 'verification' }"
                  :aria-pressed="historyView === 'verification'"
                  @click="switchHistoryView('verification')"
                >验证结果</button>
              </div>
              <span v-if="historyRevision !== null" class="field-hint">
                数据版本 {{ historyRevision }} · 截至 {{ formatMonitorUtcTime(historyAsOf) }}
              </span>
            </div>
            <p class="drawer-note">
              历史不包含原始响应；`可能已发送` 表示发送栅栏无法确认，不等于已成功。上次验证与最近调度分开展示。
            </p>
            <div v-if="historyLoading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 3" :key="item"></span></div>
            <div v-else-if="historyError" class="monitor-error-block" role="alert">
              <p>{{ historyError }}</p>
              <button type="button" class="primary-action" @click="loadHistory">刷新</button>
            </div>
            <div v-else-if="historyRuns.length === 0" class="admin-empty-state">
              <h2>该窗口内暂无记录</h2>
              <p>默认窗口为最近 24 小时；可切换视图查看调度事件或验证结果。</p>
            </div>
            <ul v-else class="monitor-history-list">
              <li v-for="run in historyRuns" :key="run.run_id" class="monitor-history-item">
                <div class="monitor-history-head">
                  <span class="monitor-state-badge" :class="run.state === 'SUCCESS' ? 'enabled' : run.state === 'FAILED' ? 'blocked' : 'disabled'">
                    {{ run.state === 'SUCCESS' ? '成功' : run.state === 'FAILED' ? '失败' : run.state === 'PENDING' ? '执行中' : run.state === 'SKIPPED' ? '已跳过' : run.state === 'UNKNOWN' ? '结果不确定' : '配置异常' }}
                  </span>
                  <span class="monitor-sub-line">{{ summaryLineFor(run.dispatch_status) }}</span>
                  <span v-if="!run.included_in_sample" class="monitor-sub-line">不计入验证样本</span>
                </div>
                <p class="monitor-sub-line">{{ adminRunPhaseLine(run) }}</p>
                <p v-if="adminRunDisplaySummary(run)" class="monitor-sub-line">{{ adminRunDisplaySummary(run) }}</p>
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
.monitor-admin-page .monitor-inline-note {
  margin: -10px 0 14px;
  padding: 9px 12px;
  border: 1px solid rgba(16, 185, 129, 0.28);
  border-radius: 10px;
  background: rgba(16, 185, 129, 0.08);
  color: #065f46;
  font-size: 12px;
}

.monitor-task-table code.monitor-cron {
  display: inline-block;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
}

.monitor-sub-line {
  display: block;
  margin-top: 4px;
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.monitor-warning-line {
  display: block;
  margin-top: 4px;
  color: #a13d2c;
  font-size: 11px;
  line-height: 1.5;
}

.monitor-state-badge {
  display: inline-flex;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 700;
}

.monitor-state-badge.enabled {
  border: 1px solid rgba(16, 185, 129, 0.32);
  background: rgba(16, 185, 129, 0.09);
  color: #065f46;
}

.monitor-state-badge.disabled {
  border: 1px solid color-mix(in srgb, var(--border-color) 80%, transparent);
  background: color-mix(in srgb, var(--bg-secondary) 70%, transparent);
  color: var(--text-secondary);
}

.monitor-state-badge.blocked {
  border: 1px solid rgba(198, 87, 70, 0.34);
  background: rgba(198, 87, 70, 0.1);
  color: #8a3a30;
}

.monitor-edit-drawer,
.monitor-history-drawer {
  width: min(560px, 100vw);
}

.monitor-field-error {
  color: #a13d2c;
  font-size: 11px;
  line-height: 1.5;
}

.monitor-enable-toggle {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
}

.monitor-enable-toggle input {
  width: 16px;
  height: 16px;
  accent-color: var(--text-primary);
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

@media (max-width: 480px) {
  .monitor-admin-page .admin-heading-actions {
    width: 100%;
    align-items: stretch;
    flex-direction: column;
  }

  .monitor-history-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .monitor-history-tabs {
    width: 100%;
  }

  .monitor-tab {
    flex: 1;
  }
}
</style>
