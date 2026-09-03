<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createAdminUser, deleteAdminUser, getAdminUser, getAdminUserApiKey, getAdminUsers, updateAdminUser } from '@/services/adminApi'
import type { AdminUser, AdminUserRole } from '@/types/admin'

const users = ref<AdminUser[]>([])
const loading = ref(true)
const refreshing = ref(false)
const keyword = ref('')
const copyingUserId = ref<number | null>(null)
const copiedUserId = ref<number | null>(null)
const saving = ref(false)
const drawerLoading = ref(false)
const nickname = ref('')
const apiKey = ref('')
const role = ref<AdminUserRole>('user')
const errorMessage = ref('')
const roleMenuOpen = ref(false)
const drawerOpen = ref(false)
const drawerMode = ref<'add' | 'edit'>('add')
const editingUserId = ref<number | null>(null)

const isEdit = computed(() => drawerMode.value === 'edit')
const editUserId = computed(() => editingUserId.value ?? 0)

const filteredUsers = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return users.value
  return users.value.filter((user) =>
    [String(user.user_id), user.nickname, user.api_key, user.role].some((field) => field.toLowerCase().includes(value)),
  )
})

async function loadUsers() {
  refreshing.value = true
  try {
    const response = await getAdminUsers()
    users.value = response.data.users
  } catch (error) {
    console.error('Failed to load admin users', error)
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function openAddDrawer() {
  drawerMode.value = 'add'
  editingUserId.value = null
  errorMessage.value = ''
  nickname.value = ''
  apiKey.value = ''
  role.value = 'user'
  drawerOpen.value = true
  roleMenuOpen.value = false
}

async function openEditDrawer(user: AdminUser) {
  drawerMode.value = 'edit'
  editingUserId.value = user.user_id
  errorMessage.value = ''
  nickname.value = ''
  apiKey.value = ''
  role.value = 'user'
  drawerOpen.value = true
  roleMenuOpen.value = false
  await loadDrawerUser()
}

async function loadDrawerUser() {
  if (!drawerOpen.value || !isEdit.value || editingUserId.value === null) return
  errorMessage.value = ''
  nickname.value = ''
  apiKey.value = ''
  role.value = 'user'
  if (!isEdit.value) return
  drawerLoading.value = true
  try {
    const response = await getAdminUser(editingUserId.value)
    nickname.value = response.data.nickname
    apiKey.value = response.data.api_key
    role.value = response.data.role
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '用户加载失败'
  } finally {
    drawerLoading.value = false
  }
}

async function handleSubmit() {
  if (!nickname.value.trim()) {
    errorMessage.value = '请填写用户昵称'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (isEdit.value) await updateAdminUser(editUserId.value, { nickname: nickname.value, role: role.value })
    else await createAdminUser({ nickname: nickname.value, role: role.value })
    // 保存请求成功后立即收起抽屉，避免刷新用户列表时让用户误以为保存失败。
    drawerOpen.value = false
    roleMenuOpen.value = false
    editingUserId.value = null
    await loadUsers()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function selectRole(nextRole: AdminUserRole) {
  role.value = nextRole
  roleMenuOpen.value = false
}

function closeDrawer() {
  if (saving.value) return
  drawerOpen.value = false
  roleMenuOpen.value = false
  editingUserId.value = null
}

async function copyApiKey(user: AdminUser) {
  copyingUserId.value = user.user_id
  try {
    const response = await getAdminUserApiKey(user.user_id)
    await navigator.clipboard.writeText(response.data.api_key)
    copiedUserId.value = user.user_id
    window.setTimeout(() => {
      if (copiedUserId.value === user.user_id) copiedUserId.value = null
    }, 1800)
  } catch (error) {
    console.error('Failed to copy API Key', error)
  } finally {
    copyingUserId.value = null
  }
}

async function handleDelete(user: AdminUser) {
  if (!window.confirm(`确认删除用户“${user.nickname}”吗？此操作不可撤销。`)) return
  await deleteAdminUser(user.user_id)
  await loadUsers()
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void loadUsers()
})
</script>

<template>
  <section class="page admin-list-page">
    <header class="admin-page-heading">
      <div>
        <div class="page-title-line">
          <span class="page-eyebrow">ADMIN / USERS</span>
          <h1>用户管理</h1>
        </div>
      </div>
      <div class="admin-heading-actions">
        <button type="button" class="primary-action" @click="openAddDrawer">添加用户</button>
        <button type="button" class="refresh-action" :disabled="refreshing" @click="loadUsers">
          <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /></svg>
          刷新列表
        </button>
      </div>
    </header>

    <section class="admin-list-card">
      <div class="admin-list-toolbar">
        <div class="admin-list-summary"><strong>{{ filteredUsers.length }}</strong><span>位用户</span></div>
        <label class="admin-search"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" /></svg><input v-model="keyword" type="search" placeholder="搜索用户昵称、ID 或 API Key" /></label>
      </div>
      <div v-if="loading" class="admin-table-skeleton" aria-hidden="true"><span v-for="item in 5" :key="item"></span></div>
      <div v-else-if="filteredUsers.length === 0" class="admin-empty-state"><h2>没有找到用户</h2><p>请尝试更换搜索关键词。</p></div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>用户 ID</th><th>用户昵称</th><th>角色</th><th>API Key</th><th>创建时间</th><th class="operation-column">操作</th></tr></thead>
          <tbody>
            <tr v-for="user in filteredUsers" :key="user.user_id">
              <td><span class="user-id">#{{ user.user_id }}</span></td>
              <td><div class="user-name-cell"><span class="user-avatar">{{ user.nickname.slice(0, 1) }}</span><strong>{{ user.nickname }}</strong></div></td>
              <td><span class="role-badge" :class="user.role">{{ user.role === 'admin' ? '管理员' : '普通用户' }}</span></td>
              <td><code>{{ user.api_key }}</code></td>
              <td><time :datetime="user.created_at">{{ formatDate(user.created_at) }}</time></td>
              <td class="operation-cell"><button type="button" class="table-action copy copy-icon" :class="{ copied: copiedUserId === user.user_id }" :aria-label="copiedUserId === user.user_id ? 'API Key 已复制' : '复制 API Key'" :title="copiedUserId === user.user_id ? 'API Key 已复制' : '复制 API Key'" :disabled="copyingUserId === user.user_id" @click="copyApiKey(user)"><svg v-if="copyingUserId === user.user_id" class="copy-spinner" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8" /></svg><svg v-else-if="copiedUserId === user.user_id" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.2L19 7" /></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="5.5" y="4.5" width="10" height="10" rx="1.8" /><rect x="8.5" y="8.5" width="10" height="10" rx="1.8" /></svg></button><button type="button" class="table-action edit" @click="openEditDrawer(user)">修改</button><button type="button" class="table-action delete" @click="handleDelete(user)">删除</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Transition name="drawer">
      <div v-if="drawerOpen" class="drawer-layer" @click.self="closeDrawer">
        <aside class="edit-drawer user-edit-drawer" aria-label="用户编辑">
          <header class="drawer-header"><div><span class="page-eyebrow">ADMIN / USERS</span><h2>{{ isEdit ? '修改用户' : '添加用户' }}</h2></div><button type="button" class="drawer-close" aria-label="关闭" @click="closeDrawer">×</button></header>
          <form class="drawer-content" @submit.prevent="handleSubmit">
            <div class="drawer-field"><label for="drawer-nickname">用户昵称</label><input id="drawer-nickname" v-model="nickname" type="text" placeholder="请输入用户昵称" :disabled="drawerLoading || saving" /></div>
            <div class="drawer-field"><label id="drawer-role-label">角色</label><div class="role-select" :class="{ open: roleMenuOpen }"><button id="drawer-role" type="button" class="role-select-trigger" aria-labelledby="drawer-role-label" :aria-expanded="roleMenuOpen" :disabled="drawerLoading || saving" @click="roleMenuOpen = !roleMenuOpen"><span>{{ role === 'admin' ? '管理员' : '普通用户' }}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg></button><Transition name="menu"><div v-if="roleMenuOpen" class="role-select-menu"><button type="button" :class="{ selected: role === 'user' }" @click="selectRole('user')"><span class="role-option-dot user"></span>普通用户<span v-if="role === 'user'" class="role-check">✓</span></button><button type="button" :class="{ selected: role === 'admin' }" @click="selectRole('admin')"><span class="role-option-dot admin"></span>管理员<span v-if="role === 'admin'" class="role-check">✓</span></button></div></Transition></div></div>
            <div v-if="isEdit" class="drawer-field"><label for="drawer-api-key">API Key</label><input id="drawer-api-key" v-model="apiKey" type="text" readonly :disabled="drawerLoading || saving" /><span class="field-hint">API Key 创建后不允许修改。</span></div>
            <p v-if="!isEdit" class="drawer-note">用户创建后，系统会自动生成 API Key。</p>
            <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
            <footer class="drawer-footer"><button type="button" class="ghost-action" :disabled="saving" @click="closeDrawer">取消</button><button type="submit" class="primary-action" :disabled="drawerLoading || saving">{{ saving ? '保存中…' : '保存用户' }}</button></footer>
          </form>
        </aside>
      </div>
    </Transition>
  </section>
</template>
