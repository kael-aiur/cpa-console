<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AdminSidebar from '@/components/layout/AdminSidebar.vue'
import { useAppStore } from '@/composables/useAppStore'
import { useSidebarDrawer } from '@/composables/useSidebarDrawer'

const appStore = useAppStore()
const router = useRouter()
const { sidebarOpen, toggleSidebar, closeSidebar } = useSidebarDrawer()

onMounted(() => {
  if (!appStore.account.value) void appStore.initializeApp()
})

async function handleLogout() {
  await appStore.logoutAccount()
  await router.push('/login')
}
</script>

<template>
  <div class="app-shell admin-shell">
    <AppHeader :sidebar-open="sidebarOpen" @toggle-sidebar="toggleSidebar" @logout="handleLogout" />
    <div class="app-body">
      <button
        type="button"
        class="sidebar-backdrop"
        :class="{ visible: sidebarOpen }"
        :aria-hidden="!sidebarOpen"
        :tabindex="sidebarOpen ? 0 : -1"
        aria-label="关闭导航菜单"
        @click="closeSidebar"
      ></button>
      <AdminSidebar :class="{ open: sidebarOpen }" @close="closeSidebar" />
      <main class="app-main">
        <RouterView />
      </main>
    </div>
  </div>
</template>
