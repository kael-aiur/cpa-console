<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAdminLiteLlmMetadata, getAdminLiteLlmSyncConfig, syncAdminLiteLlmMetadata, updateAdminLiteLlmSyncConfig } from '@/services/adminApi'
import type { AdminLiteLlmMetadata, AdminLiteLlmSyncConfig } from '@/types/admin'

const metadata = ref<AdminLiteLlmMetadata[]>([])
const config = ref<AdminLiteLlmSyncConfig>({ url: '', proxy_enabled: false, proxy_host: '127.0.0.1', proxy_port: 7890, updated_at: '' })
const loading = ref(true)
const saving = ref(false)
const syncing = ref(false)
const message = ref('')
const selectedMetadata = ref<AdminLiteLlmMetadata | null>(null)
const providerFilter = ref('')
const modelNameFilter = ref('')
const keywordFilter = ref('')

const providers = computed(() => [...new Set(metadata.value.map((model) => model.provider).filter(Boolean))].sort())
const filteredMetadata = computed(() => {
  const provider = providerFilter.value.trim().toLowerCase()
  const modelName = modelNameFilter.value.trim().toLowerCase()
  const keyword = keywordFilter.value.trim().toLowerCase()
  return metadata.value.filter((model) => {
    const modelId = model.model_id.toLowerCase()
    const rawJson = model.metadata_json.toLowerCase()
    if (provider && !model.provider.toLowerCase().includes(provider)) return false
    if (modelName && !modelId.includes(modelName)) return false
    if (keyword && !modelId.includes(keyword) && !rawJson.includes(keyword)) return false
    return true
  })
})

async function load() {
  loading.value = true
  try {
    const [models, syncConfig] = await Promise.all([getAdminLiteLlmMetadata(), getAdminLiteLlmSyncConfig()])
    metadata.value = models.data.models
    config.value = syncConfig.data
  } catch (error) {
    message.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}
async function save() {
  saving.value = true
  message.value = ''
  try { config.value = (await updateAdminLiteLlmSyncConfig(config.value)).data; message.value = '配置已保存' }
  catch (error) { message.value = error instanceof Error ? error.message : '保存失败' }
  finally { saving.value = false }
}
async function sync() {
  syncing.value = true
  message.value = ''
  try { const response = await syncAdminLiteLlmMetadata(); message.value = `同步完成，共 ${response.data.count} 条`; await load() }
  catch (error) { message.value = error instanceof Error ? error.message : '同步失败' }
  finally { syncing.value = false }
}
function openMetadata(model: AdminLiteLlmMetadata) { selectedMetadata.value = model }
function closeMetadata() { selectedMetadata.value = null }
function formatJson(value: string): string { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } }
function formatNumber(value: number | null): string { return value === null ? '—' : value.toLocaleString('en-US') }
onMounted(() => void load())
</script>

<template>
  <section class="page admin-list-page metadata-page">
    <header class="admin-page-heading"><div><div class="page-title-line"><span class="page-eyebrow">ADMIN / MODEL METADATA</span><h1>模型元数据同步配置</h1></div><p>配置 LiteLLM 模型元数据来源和访问代理。</p></div><button class="refresh-action" :disabled="syncing" @click="sync">{{ syncing ? '同步中…' : '立即同步' }}</button></header>
    <section class="admin-list-card metadata-config-card">
      <form class="metadata-config-form" @submit.prevent="save">
        <div class="drawer-field"><label for="metadata-url">元数据 URL</label><input id="metadata-url" v-model="config.url" type="url" required /></div>
        <label class="metadata-proxy-toggle"><input v-model="config.proxy_enabled" type="checkbox" /><span>使用 HTTP 代理</span></label>
        <div class="metadata-proxy-fields"><div class="drawer-field"><label for="proxy-host">代理地址</label><input id="proxy-host" v-model="config.proxy_host" :disabled="!config.proxy_enabled" /></div><div class="drawer-field"><label for="proxy-port">代理端口</label><input id="proxy-port" v-model.number="config.proxy_port" type="number" min="1" max="65535" :disabled="!config.proxy_enabled" /></div></div>
        <div class="metadata-config-footer"><button class="primary-action" :disabled="saving">{{ saving ? '保存中…' : '保存配置' }}</button><span v-if="message" class="field-hint">{{ message }}</span></div>
      </form>
    </section>
    <section class="admin-list-card metadata-list-card">
      <div class="admin-list-toolbar metadata-list-toolbar">
        <div class="admin-list-summary"><strong>{{ filteredMetadata.length }}</strong><span>条匹配结果</span></div>
        <span class="field-hint">后台每小时自动同步 · 点击卡片查看原始 JSON</span>
      </div>
      <div class="metadata-search-grid">
        <label class="metadata-search-field"><span>供应商</span><input v-model="providerFilter" list="metadata-provider-options" type="search" placeholder="搜索或选择供应商" /><datalist id="metadata-provider-options"><option v-for="provider in providers" :key="provider" :value="provider" /></datalist></label>
        <label class="metadata-search-field"><span>模型名</span><input v-model="modelNameFilter" type="search" placeholder="按模型 ID 搜索" /></label>
        <label class="metadata-search-field"><span>模型关键词</span><input v-model="keywordFilter" type="search" placeholder="搜索模型 ID 或原始 JSON" /></label>
      </div>
      <div v-if="loading" class="admin-table-skeleton"><span v-for="i in 5" :key="i"></span></div>
      <div v-else-if="metadata.length === 0" class="admin-empty-state"><h2>暂无模型元数据</h2><p>请先执行同步。</p></div>
      <div v-else-if="filteredMetadata.length === 0" class="admin-empty-state"><h2>没有找到匹配的元数据</h2><p>请尝试调整供应商、模型名或关键词。</p></div>
      <div v-else class="metadata-card-list"><button v-for="model in filteredMetadata.slice(0, 200)" :key="model.model_id" type="button" class="metadata-card" @click="openMetadata(model)"><span class="metadata-card-model-id" :title="model.model_id">{{ model.model_id }}</span><span class="metadata-card-line"><span>provider：{{ model.provider || '—' }}</span><span>模式：{{ model.mode || '—' }}</span></span><span class="metadata-card-line"><span>最大输入 Token：{{ formatNumber(model.max_input_tokens) }}</span><span>最大输出 Token：{{ formatNumber(model.max_output_tokens) }}</span></span></button></div>
    </section>
    <Transition name="drawer"><div v-if="selectedMetadata" class="drawer-layer" @click.self="closeMetadata"><aside class="edit-drawer metadata-json-drawer" aria-label="模型原始 JSON"><header class="drawer-header"><div><span class="page-eyebrow">RAW MODEL METADATA</span><h2>{{ selectedMetadata.model_id }}</h2></div><button type="button" class="drawer-close" aria-label="关闭" @click="closeMetadata">×</button></header><div class="metadata-json-content"><pre><code>{{ formatJson(selectedMetadata.metadata_json) }}</code></pre></div></aside></div></Transition>
  </section>
</template>

<style scoped>
.metadata-page .metadata-list-card { margin-top: 20px; }
.metadata-search-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; padding: 0 24px 20px; }
.metadata-search-field { display: grid; gap: 7px; color: #5f584f; font-size: 12px; font-weight: 650; }
.metadata-search-field input { min-width: 0; min-height: 38px; padding: 0 11px; border: 1px solid #d9d2c7; border-radius: 8px; background: #fffefa; color: #29251f; font: inherit; font-weight: 400; }
.metadata-search-field input:focus { border-color: #8d7c63; outline: 2px solid rgba(141, 124, 99, .15); }
.metadata-config-form { display: grid; gap: 18px; padding: 24px; }
.metadata-config-form .drawer-field { margin: 0; }
.metadata-proxy-toggle { display: inline-flex; align-items: center; gap: 10px; color: var(--ink, #28251f); font-size: 14px; cursor: pointer; }
.metadata-proxy-toggle input { width: 16px; height: 16px; accent-color: #2d2a26; }
.metadata-proxy-fields { display: grid; grid-template-columns: minmax(0, 1fr) 180px; gap: 16px; }
.metadata-config-footer { display: flex; align-items: center; gap: 16px; }
.metadata-card-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr)); gap: 12px; padding: 0 24px 24px; }
.metadata-card { min-width: 0; display: grid; gap: 10px; padding: 17px 18px; border: 1px solid #e4dfd6; border-radius: 12px; background: #fffefa; color: inherit; text-align: left; cursor: pointer; transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
.metadata-card:hover, .metadata-card:focus-visible { border-color: #b9aa94; box-shadow: 0 8px 22px rgba(52, 43, 30, .08); transform: translateY(-1px); outline: none; }
.metadata-card-model-id { display: block; overflow: hidden; color: #29251f; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 14px; font-weight: 700; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.metadata-card-line { display: flex; flex-wrap: wrap; gap: 8px 18px; color: #70695f; font-size: 13px; line-height: 1.45; }
.metadata-json-drawer { width: min(760px, 100vw); }
.metadata-json-content { min-height: 0; flex: 1; overflow: auto; padding: 24px; background: #292722; }
.metadata-json-content pre { margin: 0; color: #f4efe5; font: 12px/1.65 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; white-space: pre; tab-size: 2; }
.metadata-json-content code { font: inherit; }
@media (max-width: 700px) { .metadata-search-grid { grid-template-columns: 1fr; padding-left: 16px; padding-right: 16px; } }
@media (max-width: 640px) { .metadata-config-form, .metadata-card-list { padding-left: 16px; padding-right: 16px; } .metadata-proxy-fields { grid-template-columns: 1fr; } .metadata-config-footer { align-items: flex-start; flex-direction: column; } .metadata-card-line { display: grid; gap: 4px; } }
</style>
