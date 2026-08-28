<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AdminSidebar from '@/components/layout/AdminSidebar.vue'
import { useAppStore } from '@/composables/useAppStore'

const appStore = useAppStore()
const router = useRouter()

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
    <AppHeader @logout="handleLogout" />
    <div class="app-body">
      <AdminSidebar />
      <main class="app-main">
        <RouterView />
      </main>
    </div>
  </div>
</template>
