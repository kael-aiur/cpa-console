import { readonly, ref } from 'vue'
import { getAppInfo } from '@/services/appApi'
import { getUserInfo, logout } from '@/services/authApi'
import type { AccountInfo, AppInfo } from '@/types/app'

const appInfo = ref<AppInfo | null>(null)
const account = ref<AccountInfo | null>(null)
const userInfo = ref<{ nickname: string; role: 'user' | 'admin'; user_id: number } | null>(null)
const loading = ref(true)
const loggedOut = ref(false)

async function initializeApp() {
  loading.value = true
  try {
    const [infoResponse, userResponse] = await Promise.all([getAppInfo(), getUserInfo()])
    appInfo.value = infoResponse.data
    userInfo.value = userResponse.data
    account.value = { name: userResponse.data.nickname }
    loggedOut.value = false
  } finally {
    loading.value = false
  }
}

async function logoutAccount() {
  await logout()
  account.value = null
  userInfo.value = null
  loggedOut.value = true
}

export function useAppStore() {
  return {
    appInfo: readonly(appInfo),
    account: readonly(account),
    userInfo: readonly(userInfo),
    loading: readonly(loading),
    loggedOut: readonly(loggedOut),
    initializeApp,
    logoutAccount,
  }
}
