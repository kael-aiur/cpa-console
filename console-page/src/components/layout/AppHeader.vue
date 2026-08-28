<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAppStore } from '@/composables/useAppStore'

const emit = defineEmits<{
  logout: []
}>()

const appStore = useAppStore()
const accountMenuOpen = ref(false)
const accountMenuRef = ref<HTMLElement | null>(null)

const accountName = computed(() => appStore.account.value?.name ?? '未登录')
const roleLabel = computed(() => (appStore.userInfo.value?.role === 'admin' ? '管理员' : '普通用户'))

function toggleAccountMenu() {
  accountMenuOpen.value = !accountMenuOpen.value
}

function handleLogout() {
  accountMenuOpen.value = false
  emit('logout')
}

function handleDocumentPointerdown(event: MouseEvent) {
  if (accountMenuOpen.value && !accountMenuRef.value?.contains(event.target as Node)) {
    accountMenuOpen.value = false
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    accountMenuOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('mousedown', handleDocumentPointerdown)
  document.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleDocumentPointerdown)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <header class="app-header">
    <div ref="accountMenuRef" class="account-menu">
      <button
        type="button"
        class="account-trigger"
        :aria-expanded="accountMenuOpen"
        aria-haspopup="menu"
        @click="toggleAccountMenu"
      >
        <span class="account-avatar" aria-hidden="true">{{ accountName.slice(0, 1) }}</span>
        <span class="account-name">{{ accountName }}</span>
        <svg class="caret" :class="{ open: accountMenuOpen }" viewBox="0 0 24 24" aria-hidden="true">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      <Transition name="menu">
        <div v-if="accountMenuOpen" class="account-popover" role="menu">
          <div class="account-summary">
            <span class="account-summary-label">当前账号</span>
            <strong>{{ accountName }}</strong>
            <span class="account-role">{{ roleLabel }}</span>
          </div>
          <RouterLink
            v-if="appStore.userInfo.value?.role === 'admin'"
            class="admin-console-link"
            role="menuitem"
            to="/admin-user"
            @click="accountMenuOpen = false"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5z" />
              <path d="M8 8h8M8 12h8M8 16h5" />
            </svg>
            管理员后台
          </RouterLink>
          <button type="button" class="account-action" role="menuitem" @click="handleLogout">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <path d="m16 17 5-5-5-5" />
              <path d="M21 12H9" />
            </svg>
            退出
          </button>
        </div>
      </Transition>
    </div>
  </header>
</template>
