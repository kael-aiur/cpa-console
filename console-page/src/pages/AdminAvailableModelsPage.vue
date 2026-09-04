<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAdminAvailableModels, getAdminLiteLlmMetadata, updateAdminAvailableModel } from '@/services/adminApi'
import type { AdminAvailableModel, AdminLiteLlmMetadata } from '@/types/admin'

const models = ref<AdminAvailableModel[]>([])
const metadata = ref<AdminLiteLlmMetadata[]>([])
const loading = ref(true)
const refreshing = ref(false)
const keyword = ref('')
const editing = ref<AdminAvailableModel | null>(null)
const providerFilter = ref('')
const modelKeyword = ref('')
const modelIdFilter = ref('')
const onlyContainsCurrent = ref(false)
const selectedModelId = ref<string | null>(null)
const expandedModelId = ref<string | null>(null)
const saving = ref(false)
const error = ref('')

const filtered = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return models.value
  return models.value.filter((model) => [model.name, model.owned_by, model.litellm_model_id ?? ''].join(' ').toLowerCase().includes(key))
})
const providers = computed(() => [...new Set(metadata.value.map((model) => model.provider).filter(Boolean))].sort())
const filteredMetadata = computed(() => {
  const provider = providerFilter.value.trim().toLowerCase()
  const key = modelKeyword.value.trim().toLowerCase()
  const exactId = modelIdFilter.value.trim()
  const currentName = editing.value?.name.trim().toLowerCase() ?? ''
  const currentBareName = currentName.includes('/') ? currentName.substring(currentName.lastIndexOf('/') + 1) : currentName
  return metadata.value.filter((model) => {
    const providerName = (model.provider || '').toLowerCase()
    const modelId = (model.model_id || '').toLowerCase()
    const modelBareId = modelId.includes('/') ? modelId.substring(modelId.lastIndexOf('/') + 1) : modelId
    if (provider && !providerName.includes(provider)) return false
    if (key && !modelId.includes(key)) return false
    // The datalist is also an editable search field: typing a prefix filters results,
    // while selecting an exact option naturally narrows the result to one item.
    if (exactId && !modelId.includes(exactId.toLowerCase())) return false
    if (onlyContainsCurrent.value && currentBareName && !modelId.includes(currentName) && !modelId.includes(currentBareName) && !modelBareId.includes(currentBareName)) return false
    return true
  })
})

async function load() {
  refreshing.value = true
  try {
    const [available, liteLlm] = await Promise.all([getAdminAvailableModels(), getAdminLiteLlmMetadata()])
    models.value = available.data.models
    metadata.value = liteLlm.data.models
  } catch (exception) { console.error(exception) }
  finally { loading.value = false; refreshing.value = false }
}

function open(model: AdminAvailableModel) {
  editing.value = model
  providerFilter.value = ''
  modelKeyword.value = ''
  modelIdFilter.value = ''
  onlyContainsCurrent.value = false
  selectedModelId.value = model.litellm_model_id
  expandedModelId.value = null
  error.value = ''
}
function closeEditor() { if (!saving.value) editing.value = null }
function selectMapping(modelId: string) { selectedModelId.value = modelId }
function toggleJson(modelId: string) { expandedModelId.value = expandedModelId.value === modelId ? null : modelId }
function formatJson(value: string): string { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } }
function formatNumber(value: number | null): string { return value === null ? '—' : value.toLocaleString('en-US') }

async function save() {
  if (!editing.value) return
  saving.value = true
  error.value = ''
  try {
    const response = await updateAdminAvailableModel(editing.value.id, selectedModelId.value)
    const index = models.value.findIndex((model) => model.id === response.data.id)
    if (index >= 0) models.value[index] = response.data
    editing.value = null
  } catch (exception) { error.value = exception instanceof Error ? exception.message : '保存失败' }
  finally { saving.value = false }
}
onMounted(() => void load())
</script>

<template>
  <section class="page admin-list-page available-models-page">
    <header class="admin-page-heading"><div><div class="page-title-line"><span class="page-eyebrow">ADMIN / MODELS</span><h1>可用模型</h1></div><p>管理 CPA 同步的模型以及 LiteLLM 元数据映射。</p></div><button class="refresh-action" :disabled="refreshing" @click="load">刷新列表</button></header>
    <section class="admin-list-card">
      <div class="admin-list-toolbar"><div class="admin-list-summary"><strong>{{ filtered.length }}</strong><span>个模型</span></div><label class="admin-search"><input v-model="keyword" type="search" placeholder="搜索模型名、provider 或映射名" /></label></div>
      <div v-if="loading" class="admin-table-skeleton"><span v-for="i in 5" :key="i"></span></div>
      <div v-else-if="!filtered.length" class="admin-empty-state"><h2>没有找到模型</h2><p>请尝试更换搜索关键词。</p></div>
      <div v-else>
        <div class="admin-table-wrap available-models-desktop-list"><table class="admin-table"><thead><tr><th>模型名</th><th>所属 provider</th><th>LiteLLM 元数据模型 ID</th><th>标签</th><th>操作</th></tr></thead><tbody><tr v-for="model in filtered" :key="model.id"><td><strong>{{ model.name }}</strong></td><td><code>{{ model.owned_by || '未提供' }}</code></td><td><code>{{ model.litellm_model_id || '未关联' }}</code></td><td>{{ model.tags.join('、') || '—' }}</td><td><button class="table-action edit" @click="open(model)">修改映射</button></td></tr></tbody></table></div>
        <div class="available-models-mobile-list"><article v-for="model in filtered" :key="model.id" class="available-model-mobile-card"><div class="available-model-mobile-heading"><strong class="available-model-name">{{ model.name }}</strong><button type="button" class="table-action edit" @click="open(model)">修改映射</button></div><div class="available-model-mobile-meta"><span><b>Provider</b><span>{{ model.owned_by || '未提供' }}</span></span><span><b>LiteLLM</b><span>{{ model.litellm_model_id || '未关联' }}</span></span></div></article></div>
      </div>
    </section>

    <Transition name="drawer"><div v-if="editing" class="drawer-layer" @click.self="closeEditor"><aside class="edit-drawer mapping-drawer"><header class="drawer-header"><div><span class="page-eyebrow">MODEL MAPPING</span><h2>选择 LiteLLM 模型</h2><p class="mapping-current-model">当前 CPA 模型：<code>{{ editing.name }}</code></p></div><button type="button" class="drawer-close" aria-label="关闭" @click="closeEditor">×</button></header><div class="mapping-drawer-content">
      <div class="mapping-filters">
        <div class="mapping-filter-field"><label for="mapping-provider">供应商</label><input id="mapping-provider" v-model="providerFilter" list="provider-options" placeholder="搜索或选择供应商" /><datalist id="provider-options"><option v-for="provider in providers" :key="provider" :value="provider" /></datalist></div>
        <div class="mapping-filter-field"><label for="mapping-keyword">模型 ID 关键词</label><input id="mapping-keyword" v-model="modelKeyword" type="search" placeholder="输入模型 ID 关键词" /><label class="mapping-checkbox"><input v-model="onlyContainsCurrent" type="checkbox" /><span>仅查看包含当前模型名的模型</span></label></div>
        <div class="mapping-filter-field"><label for="mapping-model-id">模型 ID</label><input id="mapping-model-id" v-model="modelIdFilter" list="model-id-options" placeholder="搜索或选择模型 ID" /><datalist id="model-id-options"><option v-for="model in metadata" :key="model.model_id" :value="model.model_id" /></datalist></div>
      </div>
      <div class="mapping-results-heading"><span>匹配结果</span><strong>{{ filteredMetadata.length }}</strong></div>
      <div v-if="filteredMetadata.length === 0" class="mapping-empty">没有符合条件的 LiteLLM 模型。</div>
      <div v-else class="mapping-result-list"><article v-for="model in filteredMetadata" :key="model.model_id" class="mapping-result-card" :class="{ selected: selectedModelId === model.model_id }"><button type="button" class="mapping-result-main" @click="toggleJson(model.model_id)"><span class="mapping-result-id">{{ model.model_id }}</span><span class="mapping-result-meta">{{ model.provider || '未知供应商' }} · {{ model.mode || '未知模式' }}</span><span v-if="expandedModelId === model.model_id" class="mapping-result-json"><code>{{ formatJson(model.metadata_json) }}</code></span></button><button type="button" class="mapping-select-button" :class="{ selected: selectedModelId === model.model_id }" :aria-label="selectedModelId === model.model_id ? '已选择此模型' : '选择此模型'" @click.stop="selectMapping(model.model_id)"><span v-if="selectedModelId === model.model_id">✓</span><span v-else>选择</span></button></article></div>
    </div><footer class="drawer-footer"><p v-if="error" class="form-error">{{ error }}</p><button type="button" class="ghost-action" :disabled="saving" @click="closeEditor">取消</button><button type="button" class="primary-action" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存映射' }}</button></footer></aside></div></Transition>
  </section>
</template>

<style scoped>
.available-models-mobile-list { display: none; }
.available-model-mobile-card { display: grid; gap: 12px; padding: 16px; border-bottom: 1px solid #ece7de; }
.available-model-mobile-card:last-child { border-bottom: 0; }
.available-model-mobile-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.available-model-name { min-width: 0; overflow: hidden; color: #29251f; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 14px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.available-model-mobile-meta { display: grid; gap: 6px; color: #70695f; font-size: 13px; }
.available-model-mobile-meta > span { display: flex; gap: 10px; min-width: 0; }
.available-model-mobile-meta b { flex: 0 0 68px; color: #989084; font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; }
.available-model-mobile-meta > span > span:last-child { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mapping-drawer { width: min(760px, 100vw); }
.mapping-current-model { margin: 8px 0 0; color: #777066; font-size: 13px; }
.mapping-drawer-content { min-height: 0; flex: 1; overflow: auto; padding: 22px 24px 8px; }
.mapping-filters { display: grid; gap: 14px; padding: 16px; border: 1px solid #e5dfd5; border-radius: 12px; background: #fbf9f4; }
.mapping-filter-field { display: grid; gap: 7px; }
.mapping-filter-field > label:first-child { color: #5f584f; font-size: 12px; font-weight: 650; }
.mapping-filter-field input:not([type='checkbox']) { width: 100%; min-height: 40px; padding: 0 12px; border: 1px solid #d9d2c7; border-radius: 8px; background: #fffefa; color: #29251f; font: inherit; }
.mapping-filter-field input:not([type='checkbox']):focus { border-color: #8d7c63; outline: 2px solid rgba(141, 124, 99, .15); }
.mapping-checkbox { display: inline-flex; align-items: center; gap: 8px; color: #777066; font-size: 12px; cursor: pointer; }
.mapping-checkbox input { width: 15px; height: 15px; accent-color: #2d2a26; }
.mapping-results-heading { display: flex; align-items: center; gap: 8px; margin: 22px 2px 10px; color: #777066; font-size: 13px; }
.mapping-results-heading strong { display: inline-flex; min-width: 24px; height: 22px; align-items: center; justify-content: center; padding: 0 6px; border-radius: 99px; background: #eee9df; color: #544b40; font-size: 12px; }
.mapping-result-list { display: grid; gap: 8px; }
.mapping-result-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; border: 1px solid #e3ddd3; border-radius: 10px; background: #fffefa; overflow: hidden; }
.mapping-result-card.selected { border-color: #7c9b82; box-shadow: 0 0 0 2px rgba(124, 155, 130, .12); }
.mapping-result-main { display: grid; gap: 5px; min-width: 0; padding: 13px 14px; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.mapping-result-id { overflow: hidden; color: #302b25; font: 600 13px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.mapping-result-meta { color: #857d72; font-size: 12px; }
.mapping-select-button { align-self: start; min-width: 58px; margin: 11px 11px 0 0; padding: 7px 9px; border: 1px solid #d9d2c7; border-radius: 7px; background: #fffefa; color: #6b6257; font-size: 12px; cursor: pointer; }
.mapping-select-button.selected { border-color: #66856d; background: #66856d; color: white; font-weight: 700; }
.mapping-result-json { display: block; max-height: 300px; overflow: auto; margin-top: 8px; padding: 12px; border-radius: 8px; background: #292722; color: #f4efe5; }
.mapping-result-json code { display: block; font: 11px/1.6 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; white-space: pre; }
.mapping-empty { padding: 32px 12px; color: #8b8378; text-align: center; font-size: 13px; }
.mapping-drawer .drawer-footer { flex-wrap: wrap; }
.mapping-drawer .drawer-footer .form-error { flex: 1 0 100%; margin: 0; }
@media (max-width: 700px) { .available-models-desktop-list { display: none; } .available-models-mobile-list { display: block; } .mapping-drawer-content { padding-left: 16px; padding-right: 16px; } .mapping-result-card { grid-template-columns: minmax(0, 1fr) auto; } }
</style>
