import type { ApiResponse } from '@/types/app'
import type { UserInfo } from '@/types/auth'

interface CsrfResponse {
  token: string
}

async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = (await response.json()) as { code?: string; message?: string }
    if (body.message) return new Error(body.message)
  } catch {
    // Ignore non-JSON error responses and use the status fallback.
  }
  if (response.status === 401) return new Error('API Key 无效，请检查后重试')
  if (response.status === 403) return new Error('没有权限执行此操作')
  return new Error(fallback)
}

async function getCsrfToken(): Promise<string> {
  const response = await fetch('/api/csrf', { credentials: 'include' })
  if (!response.ok) throw await readError(response, '获取安全令牌失败，请重试')
  const data = (await response.json()) as CsrfResponse
  return data.token
}

export async function loginWithApiKey(apiKey: string): Promise<ApiResponse<null>> {
  const token = await getCsrfToken()
  const response = await fetch('/api/login', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
    body: JSON.stringify({ apiKey: apiKey.trim() }),
  })
  if (!response.ok) throw await readError(response, '登录失败，请重试')
  await response.json()
  return { code: 0, message: '登录成功', data: null }
}

export async function getUserInfo(): Promise<ApiResponse<UserInfo>> {
  const response = await fetch('/api/user/info', { credentials: 'include' })
  if (!response.ok) throw await readError(response, '未登录')
  const data = (await response.json()) as UserInfo
  return { code: 0, message: 'ok', data }
}

export async function logout(): Promise<ApiResponse<null>> {
  const token = await getCsrfToken()
  const response = await fetch('/api/logout', {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': token },
  })
  if (!response.ok) throw await readError(response, '退出登录失败，请重试')
  return { code: 0, message: '已退出登录', data: null }
}
