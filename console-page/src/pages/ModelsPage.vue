<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAvailableModels, getCodexModelCatalog } from '@/services/modelsApi'
import type { AvailableModel } from '@/types/models'

const models = ref<AvailableModel[]>([])
const availableTags = ref<string[]>([])
const selectedTag = ref('all')
const loading = ref(true)
const refreshing = ref(false)
const keyword = ref('')
const copiedModel = ref<string | null>(null)
const errorMessage = ref('')
const downloadingCatalog = ref(false)
const downloadErrorMessage = ref('')
const selectedModelNames = ref<string[]>([])

const selectedModels = computed(() => models.value.filter((model) => selectedModelNames.value.includes(model.name)))

const filteredModels = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return models.value.filter((model) => {
    const matchesTag = selectedTag.value === 'all' || model.tags.includes(selectedTag.value)
    if (!matchesTag) return false
    if (!value) return true
    return [model.name]
      .join(' ')
      .toLowerCase()
      .includes(value)
  })
})

async function loadModels() {
  refreshing.value = true
  errorMessage.value = ''
  try {
    const response = await getAvailableModels()
    models.value = response.data.models
    availableTags.value = response.data.tags
    if (selectedTag.value !== 'all' && !availableTags.value.includes(selectedTag.value)) selectedTag.value = 'all'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '可用模型加载失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function toggleModelSelection(model: AvailableModel) {
  if (selectedModelNames.value.includes(model.name)) selectedModelNames.value = selectedModelNames.value.filter((name) => name !== model.name)
  else selectedModelNames.value = [...selectedModelNames.value, model.name]
}
function removeSelectedModel(name: string) { selectedModelNames.value = selectedModelNames.value.filter((item) => item !== name) }

async function copyModel(model: AvailableModel) {
  try {
    await navigator.clipboard.writeText(model.name)
    copiedModel.value = model.name
    window.setTimeout(() => {
      if (copiedModel.value === model.name) copiedModel.value = null
    }, 1800)
  } catch (error) {
    console.error('Failed to copy model name', error)
  }
}

async function downloadModelCatalog() {
  downloadingCatalog.value = true
  downloadErrorMessage.value = ''
  try {
    const catalog = await getCodexModelCatalog() as { models?: Array<Record<string, unknown>> }
    const selected = new Set(selectedModelNames.value)
    const filteredCatalog = { ...catalog, models: (catalog.models ?? []).filter((model) => typeof model.slug === 'string' && selected.has(model.slug)) }
    const content = `${JSON.stringify(filteredCatalog, null, 2)}\n`
    const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'model-catalog.local.json'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    downloadErrorMessage.value = error instanceof Error ? error.message : 'ChatGPT 模型列表下载失败'
  } finally {
    downloadingCatalog.value = false
  }
}

onMounted(() => void loadModels())
</script>

<template>
  <section class="page models-page">
    <header class="admin-page-heading models-page-heading">
      <div>
        <div class="page-title-line">
          <span class="page-eyebrow">MODELS / AVAILABLE</span>
          <h1>可用模型</h1>
        </div>
      </div>
      <div class="models-heading-actions">
        <button type="button" class="refresh-action" :disabled="refreshing" @click="loadModels">
          <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /></svg>
          刷新列表
        </button>
        <button type="button" class="catalog-download-action" :disabled="downloadingCatalog || selectedModelNames.length === 0" @click="downloadModelCatalog">
          <svg :class="{ spinning: downloadingCatalog }" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M5 21h14" /></svg>
          {{ downloadingCatalog ? '下载中…' : '下载 ChatGPT 模型列表' }}
        </button>
        <span class="catalog-help" tabindex="0" aria-label="ChatGPT 模型列表使用说明">
          ?
          <span class="catalog-help-tooltip" role="tooltip">下载的 model-catalog.local.json 放到 ~/.codex/ 目录下，并在 ~/.codex/config.toml 配置 model_catalog_json = "~/.codex/model-catalog.local.json" 即可在 ChatGPT 对话中直接选择这些模型。</span>
        </span>
      </div>
    </header>

    <p v-if="downloadErrorMessage" class="catalog-download-error">{{ downloadErrorMessage }}</p>

    <section v-if="selectedModels.length" class="selected-models-panel" aria-label="已选模型"><div class="selected-models-heading"><span>已选模型</span><strong>{{ selectedModels.length }}</strong></div><div class="selected-model-tags"><span v-for="model in selectedModels" :key="model.name" class="selected-model-tag"><span :title="model.name">{{ model.name }}</span><button type="button" :aria-label="`移除 ${model.name}`" @click="removeSelectedModel(model.name)">×</button></span></div></section>

    <section class="models-list-card">
      <div class="admin-list-toolbar">
        <div class="admin-list-summary"><strong>{{ filteredModels.length }}</strong><span>个模型</span></div>
        <label class="admin-search">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
          <input v-model="keyword" type="search" placeholder="搜索模型名" />
        </label>
      </div>

      <div v-if="!loading && !errorMessage" class="models-tag-filter" role="group" aria-label="标签筛选">
        <button type="button" class="tag-tab" :class="{ active: selectedTag === 'all' }" :aria-pressed="selectedTag === 'all'" @click="selectedTag = 'all'">
          <span class="tab-glyph">◎</span><span class="tab-label">全部</span><span class="tab-count">{{ models.length }}</span>
        </button>
        <button v-for="tag in availableTags" :key="tag" type="button" class="tag-tab" :class="{ active: selectedTag === tag }" :aria-pressed="selectedTag === tag" @click="selectedTag = tag">
          <span class="tab-label">{{ tag }}</span>
        </button>
      </div>

      <div v-if="loading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 5" :key="item"></span></div>
      <div v-else-if="errorMessage" class="admin-empty-state"><h2>模型加载失败</h2><p>{{ errorMessage }}</p></div>
      <div v-else-if="filteredModels.length === 0" class="admin-empty-state"><h2>没有找到可用模型</h2><p>{{ keyword || selectedTag !== 'all' ? '请尝试更换筛选条件。' : '当前没有从 CPA 获取到可用模型。' }}</p></div>
      <div v-else class="admin-table-wrap models-table-wrap">
        <table class="admin-table models-table">
          <thead><tr><th>模型名</th><th>标签</th></tr></thead>
          <tbody>
            <tr v-for="model in filteredModels" :key="model.name" :class="{ 'model-selected-row': selectedModelNames.includes(model.name) }" @click="toggleModelSelection(model)">
              <td><div class="model-name-cell"><span class="model-selection-indicator" aria-hidden="true">{{ selectedModelNames.includes(model.name) ? '✓' : '' }}</span><code>{{ model.name }}</code><button type="button" class="table-action copy copy-icon" :class="{ copied: copiedModel === model.name }" :aria-label="copiedModel === model.name ? '模型名已复制' : '复制模型名'" :title="copiedModel === model.name ? '模型名已复制' : '复制模型名'" @click.stop="copyModel(model)"><svg v-if="copiedModel === model.name" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.2L19 7" /></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="5.5" y="4.5" width="10" height="10" rx="1.8" /><rect x="8.5" y="8.5" width="10" height="10" rx="1.8" /></svg></button></div></td>
              <td><div class="model-tags"><span v-for="tag in model.tags" :key="tag" class="file-tag">{{ tag }}</span><span v-if="model.tags.length === 0" class="model-no-tag">未分类</span></div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.selected-models-panel { margin: 0 0 16px; padding: 14px 16px; border: 1px solid var(--glass-border); border-radius: 14px; background: color-mix(in srgb, var(--bg-primary) 74%, transparent); box-shadow: var(--shadow); }
.selected-models-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; color: var(--text-secondary); font-size: 12px; font-weight: 700; }
.selected-models-heading strong { display: inline-flex; min-width: 22px; height: 20px; align-items: center; justify-content: center; padding: 0 6px; border-radius: 99px; background: var(--bg-tertiary); color: var(--text-primary); font-size: 11px; }
.selected-model-tags { display: flex; flex-wrap: wrap; gap: 7px; }
.selected-model-tag { display: inline-flex; max-width: 100%; align-items: center; gap: 6px; padding: 6px 8px 6px 10px; border: 1px solid color-mix(in srgb, var(--success-color) 35%, var(--glass-border)); border-radius: 999px; background: color-mix(in srgb, var(--success-color) 10%, var(--bg-primary)); color: var(--text-primary); font: 11px/1.2 ui-monospace, 'SF Mono', Menlo, monospace; }
.selected-model-tag > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.selected-model-tag button { display: grid; width: 17px; height: 17px; flex: 0 0 17px; place-items: center; padding: 0; cursor: pointer; border: 0; border-radius: 50%; background: transparent; color: var(--text-tertiary); font-size: 15px; line-height: 1; }
.selected-model-tag button:hover { background: var(--bg-tertiary); color: var(--text-primary); }
.models-table tbody tr { cursor: pointer; }
.models-table tbody tr.model-selected-row { background: color-mix(in srgb, var(--success-color) 8%, transparent); }
.model-selection-indicator { display: grid; width: 17px; height: 17px; flex: 0 0 17px; place-items: center; border: 1px solid var(--border-primary); border-radius: 5px; color: var(--success-color); font-size: 12px; font-weight: 800; }
.model-selected-row .model-selection-indicator { border-color: var(--success-color); background: color-mix(in srgb, var(--success-color) 14%, transparent); }
@media (max-width: 680px) { .selected-models-panel { margin-bottom: 12px; padding: 12px; } }
</style>
