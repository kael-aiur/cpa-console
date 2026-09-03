import { createRouter, createWebHashHistory } from 'vue-router'
import LoginPage from '@/pages/LoginPage.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import AdminUserPage from '@/pages/AdminUserPage.vue'
import AdminCredentialsPage from '@/pages/AdminCredentialsPage.vue'
import AdminUsagePage from '@/pages/AdminUsagePage.vue'
import AdminModelExperiencePage from '@/pages/AdminModelExperiencePage.vue'
import QuotaPage from '@/pages/QuotaPage.vue'
import UsagePage from '@/pages/UsagePage.vue'
import ModelsPage from '@/pages/ModelsPage.vue'
import { getUserInfo } from '@/services/authApi'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage, meta: { guestOnly: true, title: '登录' } },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/usage' },
        { path: 'quota', name: 'quota', component: QuotaPage, meta: { title: '额度查看' } },
        { path: 'models', name: 'models', component: ModelsPage, meta: { title: '可用模型' } },
        { path: 'usage', name: 'usage', component: UsagePage, meta: { title: '用量查看' } },
      ],
    },
    // 管理员后台默认进入用量统计。
    { path: '/admin', redirect: '/admin-usage' },
    {
      path: '/admin-user',
      component: AdminLayout,
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [{ path: '', name: 'admin-user', component: AdminUserPage, meta: { title: '用户管理' } }],
    },
    {
      path: '/admin-credentials',
      component: AdminLayout,
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [{ path: '', name: 'admin-credentials', component: AdminCredentialsPage, meta: { title: '凭证管理' } }],
    },
    {
      path: '/admin-usage',
      component: AdminLayout,
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [{ path: '', name: 'admin-usage', component: AdminUsagePage, meta: { title: '用量统计' } }],
    },
    {
      path: '/admin-model-experience',
      component: AdminLayout,
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [{ path: '', name: 'admin-model-experience', component: AdminModelExperiencePage, meta: { title: '模型体验' } }],
    },
  ],
})

router.beforeEach(async (to) => {
  const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
  const isAdminRoute = to.path.startsWith('/admin-')
  try {
    const response = await getUserInfo()
    if (to.meta.guestOnly) return { name: 'usage' }
    if (requiresAuth && isAdminRoute && response.data.role !== 'admin') return { name: 'usage' }
    return true
  } catch {
    // 登录页本身允许匿名访问；未登录时必须放行当前路由，避免重定向到自身形成循环。
    if (to.meta.guestOnly) return true
    if (requiresAuth) return { name: 'login' }
    return true
  }
})
