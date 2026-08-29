<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAppInfo } from '@/services/appApi'
import { loginWithApiKey } from '@/services/authApi'

const router = useRouter()
const apiKey = ref('')
const projectName = ref('CPA Console')
const logo = ref('')
const loading = ref(false)
const errorMessage = ref('')

const canSubmit = computed(() => apiKey.value.trim().length > 0 && !loading.value)

async function handleLogin() {
  if (!canSubmit.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    await loginWithApiKey(apiKey.value)
    await router.push('/usage')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const response = await getAppInfo()
  projectName.value = response.data.projectName
  logo.value = response.data.logoBase64
})
</script>

<template>
  <main class="login-page">
    <div class="login-glow" aria-hidden="true"></div>
    <section class="login-card">
      <div class="login-brand">
        <img v-if="logo" :src="logo" class="login-logo" alt="项目 Logo" />
        <span v-else class="login-logo login-logo-loading" aria-hidden="true"></span>
        <div>
          <span class="login-kicker">Welcome back</span>
          <h1>{{ projectName }}</h1>
        </div>
      </div>

      <div class="login-copy">
        <h2>登录控制台</h2>
        <p>使用 API Key 继续访问你的额度与用量信息。</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <label for="api-key">API Key</label>
        <input
          id="api-key"
          v-model="apiKey"
          type="password"
          autocomplete="current-password"
          placeholder="请输入 API Key"
          :disabled="loading"
        />
        <p v-if="errorMessage" class="login-error" role="alert">{{ errorMessage }}</p>
        <button type="submit" class="login-submit" :disabled="!canSubmit">
          {{ loading ? '登录中…' : '登录' }}
          <span v-if="!loading" aria-hidden="true">→</span>
        </button>
      </form>

      <p class="login-hint">登录成功后，凭证将由当前域名 Cookie 保存。</p>
    </section>
  </main>
</template>
