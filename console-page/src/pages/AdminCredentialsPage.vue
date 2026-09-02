<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAdminCredentials, updateAdminCredentialTags } from '@/services/adminApi'
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
  refreshing.value = true
  try {
    const response = await getAdminCredentials()
    credentials.value = response.data.credentials
  } catch (error) {
    console.error('Failed to load credentials', error)
  } finally {
    loading.value = false
    refreshing.value = false
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
        <span class="page-eyebrow">ADMIN / CREDENTIALS</span>
        <h1>凭证管理</h1>
        <p>查看 CPA 服务中的凭证映射关系，并维护凭证标签。</p>
      </div>
      <button type="button" class="refresh-action" :disabled="refreshing" @click="loadCredentials">
        <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" />
          <path d="M21 3v5h-5" />
        </svg>
        刷新列表
      </button>
    </header>

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
            <thead><tr><th>凭证名称</th><th>凭证类型</th><th>状态</th><th>引用 ID</th><th>标签</th><th class="operation-column">操作</th></tr></thead>
            <tbody>
              <tr v-for="credential in filteredCredentials" :key="credential.id">
                <td><strong class="credential-name">{{ credential.name }}</strong></td>
                <td><span class="credential-type">{{ formatType(credential.credential_type) }}</span></td>
                <td><span class="credential-status" :class="credential.enabled ? 'enabled' : 'disabled'"><i></i>{{ credential.enabled ? '可用' : '停用' }}</span></td>
                <td><code>{{ credential.reference_id }}</code></td>
                <td><div class="credential-tags"><span v-for="tag in credential.tags" :key="tag" class="file-tag">{{ tag }}</span></div></td>
                <td class="operation-cell"><button type="button" class="table-action edit" @click="openEditor(credential)">修改</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="credentials-mobile-list">
          <article v-for="credential in filteredCredentials" :key="credential.id" class="credential-mobile-card">
            <div class="credential-mobile-heading">
              <strong class="credential-name">{{ credential.name }}</strong>
              <button type="button" class="table-action edit" @click="openEditor(credential)">修改</button>
            </div>
            <div class="credential-mobile-meta">
              <span class="credential-type">{{ formatType(credential.credential_type) }}</span>
              <span class="credential-status" :class="credential.enabled ? 'enabled' : 'disabled'"><i></i>{{ credential.enabled ? '可用' : '停用' }}</span>
            </div>
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
