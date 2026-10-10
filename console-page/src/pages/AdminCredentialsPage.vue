<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import CredentialQuotaDisplay from '@/components/CredentialQuotaDisplay.vue'
import type { AccountQuota } from '@/types/quota'
import { getAdminCredentialQuota, resetAdminCredentialQuota, CredentialResetError, getAdminCredentials, updateAdminCredentialTags } from '@/services/adminApi'
import type { AdminCredential } from '@/types/credentials'

const credentials = ref<AdminCredential[]>([])
const loading = ref(true)
const refreshing = ref(false)
const keyword = ref('')
const drawerOpen = ref(false)
const saving = ref(false)
const editingCredential = ref<AdminCredential | null>(null)
const editingTags = ref<string[]>([])
const newTag = ref('')
const errorMessage = ref('')
const listError = ref('')
const quotas = ref<Record<number, AccountQuota | undefined>>({})
const quotaLoading = ref<Record<number, boolean>>({})
const quotaErrors = ref<Record<number, string>>({})
const resetting = ref<Record<number, boolean>>({})
const resetMessages = ref<Record<number, string>>({})
const resetBlocked = ref<Record<number, boolean>>({})
const anyResetting = computed(() => Object.values(resetting.value).some(Boolean))
let generation = 0
const quotaVersions: Record<number, number> = {}
onBeforeUnmount(() => { generation++ })

function canReset(credential: AdminCredential): boolean {
  return credential.credential_type === 'auth_file' && credential.provider === 'codex'
}

async function loadQuota(credential: AdminCredential, listGeneration = generation): Promise<boolean> {
  const version = (quotaVersions[credential.id] ?? 0) + 1
  quotaVersions[credential.id] = version
  quotaLoading.value[credential.id] = true
  quotaErrors.value[credential.id] = ''
  const current = () => generation === listGeneration && quotaVersions[credential.id] === version
  try {
    const quota = await getAdminCredentialQuota(credential)
    if (!current()) return false
    quotas.value[credential.id] = quota
    return true
  } catch (error) {
    if (current()) {
      quotas.value[credential.id] = undefined
      quotaErrors.value[credential.id] = error instanceof Error ? error.message : '额度查询失败'
    }
    return false
  } finally {
    if (current()) quotaLoading.value[credential.id] = false
  }
}

async function resetQuota(credential: AdminCredential) {
  if (!canReset(credential) || resetting.value[credential.id] || resetBlocked.value[credential.id]) return
  if (!window.confirm(`确认重置 ${credential.name} 的额度并清除 CPA 冷却状态？此操作可能消耗可用重置次数。`)) return
  resetting.value[credential.id] = true
  resetMessages.value[credential.id] = ''
  const listGeneration = generation
  const current = () => generation === listGeneration
  try {
    const response = await resetAdminCredentialQuota(credential.id)
    if (!current()) return
    resetMessages.value[credential.id] = response.message
    const refreshed = await loadQuota(credential, listGeneration)
    if (current() && !refreshed) resetMessages.value[credential.id] = '重置成功，额度刷新失败，请重试查询'
  } catch (error) {
    if (!current()) return
    resetMessages.value[credential.id] = error instanceof Error ? error.message : '额度重置失败'
    if (error instanceof CredentialResetError && error.result.quota_reset !== 'failed') {
      // A partial/unknown result must not invite another quota-consuming reset.
      resetBlocked.value[credential.id] = true
      await loadQuota(credential, listGeneration)
    }
  } finally {
    resetting.value[credential.id] = false
  }
}

const filteredCredentials = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return credentials.value
  return credentials.value.filter((credential) =>
    [credential.name, credential.credential_type, credential.reference_id, ...credential.tags]
      .join(' ')
      .toLowerCase()
      .includes(value),
  )
})

async function loadCredentials() {
  if (anyResetting.value) return
  const listGeneration = ++generation
  refreshing.value = true
  listError.value = ''
  try {
    const response = await getAdminCredentials()
    if (generation !== listGeneration) return
    credentials.value = response.data.credentials
    loading.value = false
    quotas.value = {}
    quotaErrors.value = {}
    quotaLoading.value = Object.fromEntries(credentials.value.map(item => [item.id, true]))
    for (let start = 0; start < credentials.value.length; start += 10) {
      if (generation !== listGeneration) return
      await Promise.all(credentials.value.slice(start, start + 10).map(item => loadQuota(item, listGeneration)))
    }
  } catch (error) {
    if (generation === listGeneration) listError.value = error instanceof Error ? error.message : '凭证列表加载失败'
  } finally {
    if (generation === listGeneration) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function openEditor(credential: AdminCredential) {
  editingCredential.value = credential
  editingTags.value = [...credential.tags]
  newTag.value = ''
  errorMessage.value = ''
  drawerOpen.value = true
}

function closeEditor() {
  if (saving.value) return
  drawerOpen.value = false
  editingCredential.value = null
}

function addTag() {
  const tag = newTag.value.trim()
  if (!tag || editingTags.value.includes(tag)) return
  editingTags.value.push(tag)
  newTag.value = ''
}

function removeTag(index: number) {
  editingTags.value.splice(index, 1)
}

async function saveTags() {
  if (!editingCredential.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    const response = await updateAdminCredentialTags(editingCredential.value.id, editingTags.value)
    const index = credentials.value.findIndex((item) => item.id === response.data.id)
    if (index >= 0) credentials.value[index] = response.data
    // closeEditor intentionally blocks while saving; mark the request complete first.
    saving.value = false
    closeEditor()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function formatType(type: AdminCredential['credential_type']): string {
  return type === 'auth_file' ? 'auth_file' : 'apikey'
}

onMounted(() => void loadCredentials())
</script>

<template>
  <section class="page admin-list-page">
    <header class="admin-page-heading">
      <div>
        <div class="page-title-line">
          <span class="page-eyebrow">ADMIN / CREDENTIALS</span>
          <h1>凭证管理</h1>
        </div>
      </div>
      <button type="button" class="refresh-action" :disabled="refreshing || anyResetting" @click="loadCredentials">
        <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" />
          <path d="M21 3v5h-5" />
        </svg>
        刷新列表
      </button>
    </header>

    <p v-if="listError" class="form-error" role="alert">{{ listError }}</p>
    <section class="admin-list-card">
      <div class="admin-list-toolbar">
        <div class="admin-list-summary"><strong>{{ filteredCredentials.length }}</strong><span>条凭证</span></div>
        <label class="admin-search">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
          <input v-model="keyword" type="search" placeholder="搜索名称、类型、引用 ID 或标签" />
        </label>
      </div>

      <div v-if="loading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 5" :key="item"></span></div>
      <div v-else-if="filteredCredentials.length === 0" class="admin-empty-state"><h2>没有找到凭证</h2><p>请尝试更换搜索关键词。</p></div>
      <div v-else>
        <div class="admin-table-wrap credentials-desktop-list">
          <table class="admin-table credentials-table">
            <thead><tr><th>凭证名称</th><th>凭证类型</th><th>状态</th><th>额度</th><th>引用 ID</th><th>标签</th><th class="operation-column">操作</th></tr></thead>
            <tbody>
              <tr v-for="credential in filteredCredentials" :key="credential.id">
                <td><strong class="credential-name">{{ credential.name }}</strong></td>
                <td><span class="credential-type">{{ formatType(credential.credential_type) }}</span></td>
                <td><span class="credential-status" :class="credential.enabled ? 'enabled' : 'disabled'"><i></i>{{ credential.enabled ? '可用' : '停用' }}</span></td>
                <td class="credential-quota-cell"><CredentialQuotaDisplay :quota="quotas[credential.id]" :loading="quotaLoading[credential.id]" :error="quotaErrors[credential.id]" @retry="loadQuota(credential)" /></td>
                <td><code>{{ credential.reference_id }}</code></td>
                <td><div class="credential-tags"><span v-for="tag in credential.tags" :key="tag" class="file-tag">{{ tag }}</span></div></td>
                <td class="operation-cell">
                  <div class="credential-actions">
                    <button type="button" class="table-action edit" @click="openEditor(credential)">修改</button>
                    <button v-if="canReset(credential)" type="button" class="table-action" :disabled="resetting[credential.id] || resetBlocked[credential.id]" @click="resetQuota(credential)">{{ resetting[credential.id] ? '重置中…' : '重置' }}</button>
                  </div>
                  <p v-if="resetMessages[credential.id]" class="credential-reset-message" role="status">{{ resetMessages[credential.id] }}</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="credentials-mobile-list">
          <article v-for="credential in filteredCredentials" :key="credential.id" class="credential-mobile-card">
            <div class="credential-mobile-heading">
              <strong class="credential-name">{{ credential.name }}</strong>
              <div class="credential-actions">
                <button type="button" class="table-action edit" @click="openEditor(credential)">修改</button>
                <button v-if="canReset(credential)" type="button" class="table-action" :disabled="resetting[credential.id] || resetBlocked[credential.id]" @click="resetQuota(credential)">{{ resetting[credential.id] ? '重置中…' : '重置' }}</button>
              </div>
            </div>
            <div class="credential-mobile-meta">
              <span class="credential-type">{{ formatType(credential.credential_type) }}</span>
              <span class="credential-status" :class="credential.enabled ? 'enabled' : 'disabled'"><i></i>{{ credential.enabled ? '可用' : '停用' }}</span>
            </div>
            <CredentialQuotaDisplay :quota="quotas[credential.id]" :loading="quotaLoading[credential.id]" :error="quotaErrors[credential.id]" @retry="loadQuota(credential)" />
            <p v-if="resetMessages[credential.id]" class="credential-reset-message" role="status">{{ resetMessages[credential.id] }}</p>
            <code class="credential-mobile-reference">{{ credential.reference_id }}</code>
            <div v-if="credential.tags.length" class="credential-tags">
              <span v-for="tag in credential.tags" :key="tag" class="file-tag">{{ tag }}</span>
            </div>
          </article>
        </div>
      </div>
    </section>

    <Transition name="drawer">
      <div v-if="drawerOpen" class="drawer-layer" @click.self="closeEditor">
        <aside class="edit-drawer" aria-label="修改凭证标签">
          <header class="drawer-header"><div><span class="page-eyebrow">EDIT CREDENTIAL</span><h2>修改凭证</h2></div><button type="button" class="drawer-close" aria-label="关闭" @click="closeEditor">×</button></header>
          <div class="drawer-content">
            <div class="drawer-readonly"><span>凭证名称</span><strong>{{ editingCredential?.name }}</strong></div>
            <div class="drawer-readonly"><span>凭证类型</span><code>{{ editingCredential?.credential_type }}</code></div>
            <div class="drawer-readonly"><span>状态</span><span class="credential-status" :class="editingCredential?.enabled ? 'enabled' : 'disabled'"><i></i>{{ editingCredential?.enabled ? '可用' : '停用' }}</span></div>
            <div class="drawer-readonly"><span>引用 ID</span><code>{{ editingCredential?.reference_id }}</code></div>
            <div class="drawer-field"><label for="credential-tags">标签</label><div id="credential-tags" class="drawer-tags"><span v-for="(tag, index) in editingTags" :key="tag" class="file-tag editable-tag">{{ tag }}<button type="button" :aria-label="`删除标签 ${tag}`" @click="removeTag(index)">×</button></span><span v-if="editingTags.length === 0" class="drawer-empty-tags">暂无标签</span></div><div class="tag-input-row"><input v-model="newTag" type="text" placeholder="输入标签后回车" @keydown.enter.prevent="addTag" /><button type="button" class="tag-add-button" @click="addTag">添加</button></div></div>
            <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
          </div>
          <footer class="drawer-footer"><button type="button" class="ghost-action" :disabled="saving" @click="closeEditor">取消</button><button type="button" class="primary-action" :disabled="saving" @click="saveTags">{{ saving ? '保存中…' : '保存修改' }}</button></footer>
        </aside>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.credentials-table { min-width: 1100px; }
.credential-quota-cell { min-width: 240px; white-space: normal; }
.credential-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.credential-reset-message { max-width: 180px; font-size: 12px; white-space: normal; overflow-wrap: anywhere; color: var(--text-secondary); }
.credential-actions button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 768px) {
  .credential-mobile-heading { align-items: flex-start; flex-wrap: wrap; gap: 8px; }
  .credential-reset-message { max-width: 100%; }
}
</style>
