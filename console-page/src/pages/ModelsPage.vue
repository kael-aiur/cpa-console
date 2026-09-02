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
    const catalog = await getCodexModelCatalog()
    const content = `${JSON.stringify(catalog, null, 2)}\n`
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
        <span class="page-eyebrow">MODELS / AVAILABLE</span>
        <h1>可用模型</h1>
        <p>查看当前 CPA 凭证支持的模型，并快速复制模型名。</p>
      </div>
      <div class="models-heading-actions">
        <button type="button" class="refresh-action" :disabled="refreshing" @click="loadModels">
          <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /></svg>
          刷新列表
        </button>
        <button type="button" class="catalog-download-action" :disabled="downloadingCatalog" @click="downloadModelCatalog">
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
            <tr v-for="model in filteredModels" :key="model.name">
              <td><div class="model-name-cell"><code>{{ model.name }}</code><button type="button" class="table-action copy copy-icon" :class="{ copied: copiedModel === model.name }" :aria-label="copiedModel === model.name ? '模型名已复制' : '复制模型名'" :title="copiedModel === model.name ? '模型名已复制' : '复制模型名'" @click="copyModel(model)"><svg v-if="copiedModel === model.name" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.2L19 7" /></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="5.5" y="4.5" width="10" height="10" rx="1.8" /><rect x="8.5" y="8.5" width="10" height="10" rx="1.8" /></svg></button></div></td>
              <td><div class="model-tags"><span v-for="tag in model.tags" :key="tag" class="file-tag">{{ tag }}</span><span v-if="model.tags.length === 0" class="model-no-tag">未分类</span></div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>
