import type { ApiResponse } from '@/types/app'
import type { AdminUser, AdminUserListResponse } from '@/types/admin'

interface AdminUserPayload { nickname: string; role: 'admin' | 'user' }
interface CsrfResponse { token: string }

async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body.message) return new Error(body.message)
  } catch {
    // Ignore empty or non-JSON error responses.
  }
  if (response.status === 401) return new Error('登录状态已失效，请重新登录')
  if (response.status === 403) return new Error('没有权限执行此操作')
  if (response.status === 404) return new Error('用户不存在')
  return new Error(fallback)
}

async function getCsrfToken(): Promise<string> {
  const response = await fetch('/api/csrf', { credentials: 'include' })
  if (!response.ok) throw await readError(response, '获取安全令牌失败，请重试')
  return ((await response.json()) as CsrfResponse).token
}

async function request<T>(path: string, init: RequestInit = {}, fallback = '请求失败'): Promise<ApiResponse<T>> {
  const response = await fetch(path, { ...init, credentials: 'include' })
  if (!response.ok) throw await readError(response, fallback)
  if (response.status === 204) return { code: 0, message: 'ok', data: null as T }
  return { code: 0, message: 'ok', data: (await response.json()) as T }
}

async function mutate<T>(path: string, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', body?: unknown, fallback = '请求失败'): Promise<ApiResponse<T>> {
  const token = await getCsrfToken()
  return request<T>(path, {
    method,
    headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), 'X-XSRF-TOKEN': token },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }, fallback)
}

export function getAdminUsers(): Promise<ApiResponse<AdminUserListResponse>> {
  return request('/admin/users', {}, '用户列表加载失败')
}

export function getAdminUser(userId: number): Promise<ApiResponse<AdminUser>> {
  return request(`/admin/users/${userId}`, {}, '用户加载失败')
}

export function createAdminUser(payload: AdminUserPayload): Promise<ApiResponse<AdminUser>> {
  return mutate('/admin/users', 'POST', payload, '用户创建失败，请重试')
}

export function updateAdminUser(userId: number, payload: AdminUserPayload): Promise<ApiResponse<AdminUser>> {
  return mutate(`/admin/users/${userId}`, 'PUT', payload, '用户更新失败，请重试')
}

export function getAdminUserApiKey(userId: number): Promise<ApiResponse<{ api_key: string }>> {
  return request(`/admin/users/${userId}/api-key`, {}, 'API Key 获取失败')
}

export function deleteAdminUser(userId: number): Promise<ApiResponse<null>> {
  return mutate(`/admin/users/${userId}`, 'DELETE', undefined, '用户删除失败，请重试')
}

import type { AdminCredential, AdminCredentialListResponse } from '@/types/credentials'

export function getAdminCredentials(): Promise<ApiResponse<AdminCredentialListResponse>> {
  return request('/admin/credentials', {}, '凭证列表加载失败')
}

export function updateAdminCredentialTags(id: number, tags: string[]): Promise<ApiResponse<AdminCredential>> {
  return mutate(`/admin/credentials/${id}`, 'PATCH', { tags }, '凭证标签更新失败，请重试')
}

export interface AdminUsageUser {
  user_id: number
  nickname: string
  role: 'admin' | 'user'
}

export interface AdminUsageSummary {
  total_requests: number
  total_tokens: number
  average_duration_ms: number
  model_token_distribution: Record<string, number>
  model_request_distribution: Record<string, number>
  user_token_distribution: Record<string, number>
  user_request_distribution: Record<string, number>
}

export interface AdminUsageRecordsResponse {
  records: import('@/types/usage').UsageRecord[]
  page: number
  page_size: number
  total: number
  total_pages: number
}

function usageQuery(range: { start: string; end: string }, userId?: number | null): string {
  const params = new URLSearchParams({ start: range.start, end: range.end })
  if (userId !== null && userId !== undefined) params.set('user_id', String(userId))
  return params.toString()
}

export function getAdminUsageUsers(): Promise<ApiResponse<{ users: AdminUsageUser[] }>> {
  return request('/admin/usage/users', {}, '用量用户列表加载失败')
}

export function getAdminUsageSummary(range: { start: string; end: string }, userId?: number | null): Promise<ApiResponse<AdminUsageSummary>> {
  return request(`/admin/usage/summary?${usageQuery(range, userId)}`, {}, '用量汇总加载失败')
}

export function getAdminUsageRecords(range: { start: string; end: string }, page: number, pageSize: number, userId?: number | null): Promise<ApiResponse<AdminUsageRecordsResponse>> {
  return request(`/admin/usage/records?${usageQuery(range, userId)}&page=${page}&page_size=${pageSize}`, {}, '请求记录加载失败')
}
