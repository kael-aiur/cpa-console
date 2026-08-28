<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useAppStore } from '@/composables/useAppStore'

const appStore = useAppStore()
const router = useRouter()

onMounted(() => {
  void appStore.initializeApp()
})

async function handleLogout() {
  await appStore.logoutAccount()
  await router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <AppHeader @logout="handleLogout" />

    <div v-if="appStore.loggedOut.value" class="logout-banner" role="status">
      已退出当前账号（Mock 环境保留界面）
      <button type="button" @click="appStore.initializeApp()">重新进入</button>
    </div>

    <div class="app-body">
      <AppSidebar />
      <main class="app-main">
        <RouterView />
      </main>
    </div>
  </div>
</template>
