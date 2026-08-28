<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { MOCK_PROJECT_LOGO_BASE64 } from '@/mocks/appMockData'

const route = useRoute()

function updateBrowserChrome() {
  const pageName = typeof route.meta.title === 'string' ? route.meta.title : '控制台'
  document.title = `乌蝇哥-${pageName}`

  let favicon = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!favicon) {
    favicon = document.createElement('link')
    favicon.rel = 'icon'
    document.head.appendChild(favicon)
  }
  favicon.type = 'image/jpeg'
  favicon.href = MOCK_PROJECT_LOGO_BASE64
}

watch(() => [route.fullPath, route.meta.title], updateBrowserChrome, { immediate: true })
</script>

<template>
  <RouterView />
</template>
