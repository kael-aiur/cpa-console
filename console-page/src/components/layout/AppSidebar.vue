<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/composables/useAppStore'

const appStore = useAppStore()

const route = useRoute()

const menus = [
  { path: '/usage', label: '用量查看', description: '请求与消耗统计' },
  { path: '/quota', label: '额度查看', description: '账户余额与配额' },
  { path: '/models', label: '可用模型', description: '模型与凭证支持' },
]

const activePath = computed(() => route.path)
const projectName = computed(() => appStore.appInfo.value?.projectName ?? 'CPA Console')
const logo = computed(() => appStore.appInfo.value?.logoBase64 ?? '')
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand" aria-label="项目信息">
      <img v-if="logo" class="brand-logo" :src="logo" alt="项目 Logo" />
      <span v-else class="brand-logo brand-logo-loading" aria-hidden="true"></span>
      <span class="brand-name">{{ projectName }}</span>
    </div>

    <nav class="sidebar-nav" aria-label="主导航">
      <RouterLink
        v-for="item in menus"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: activePath === item.path }"
      >
        <span class="nav-indicator" aria-hidden="true"></span>
        <span class="nav-copy">
          <span class="nav-label">{{ item.label }}</span>
          <span class="nav-description">{{ item.description }}</span>
        </span>
      </RouterLink>
    </nav>

  </aside>
</template>
