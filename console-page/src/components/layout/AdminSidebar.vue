<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/composables/useAppStore'

const route = useRoute()
const appStore = useAppStore()
const menus = [
  { path: '/admin-usage', label: '用量统计', description: '全局请求与消耗统计' },
  { path: '/admin-model-experience', label: '模型体验', description: '与模型进行临时对话' },
  { path: '/admin-user', label: '用户管理', description: '账号与权限管理' },
  { path: '/admin-credentials', label: '凭证管理', description: '凭证与授权管理' },
]
const emit = defineEmits<{
  close: []
}>()

const activePath = computed(() => route.path)
const projectName = computed(() => appStore.appInfo.value?.projectName ?? 'CPA Console')
const logo = computed(() => appStore.appInfo.value?.logoBase64 ?? '')

function closeSidebar() {
  emit('close')
}
</script>

<template>
  <aside id="app-sidebar" class="sidebar admin-sidebar">
    <div class="sidebar-brand" aria-label="管理员控制台项目信息">
      <img v-if="logo" class="brand-logo" :src="logo" alt="项目 Logo" />
      <span v-else class="brand-logo brand-logo-loading" aria-hidden="true"></span>
      <span class="brand-name">{{ projectName }}</span>
    </div>
    <div class="admin-console-label">管理员控制台</div>

    <nav class="sidebar-nav" aria-label="管理员导航">
      <RouterLink
        v-for="item in menus"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: activePath === item.path }"
        @click="closeSidebar"
      >
        <span class="nav-indicator" aria-hidden="true"></span>
        <span class="nav-copy">
          <span class="nav-label">{{ item.label }}</span>
          <span class="nav-description">{{ item.description }}</span>
        </span>
      </RouterLink>
    </nav>

    <RouterLink class="back-to-user" to="/usage" @click="closeSidebar">返回用户后台</RouterLink>
  </aside>
</template>
