import type { ApiResponse } from '@/types/app'
import type { UsageRecordsResponse, UsageSummary, UsageTimeRange } from '@/types/usage'

async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = (await response.json()) as { code?: string; message?: string }
    if (body.message) return new Error(body.message)
  } catch {
    // Use the fallback for non-JSON responses.
  }
  if (response.status === 401) return new Error('登录状态已失效，请重新登录')
  if (response.status === 403) return new Error('没有权限执行此操作')
  return new Error(fallback)
}

async function request<T>(path: string, fallback: string): Promise<T> {
  const response = await fetch(path, { credentials: 'include' })
  if (!response.ok) throw await readError(response, fallback)
  return (await response.json()) as T
}

function query(range: UsageTimeRange): string {
  return `start=${encodeURIComponent(range.start)}&end=${encodeURIComponent(range.end)}`
}

export async function getUsageSummary(range: UsageTimeRange): Promise<ApiResponse<UsageSummary>> {
  const data = await request<UsageSummary>(`/api/usage/summary?${query(range)}`, '用量汇总加载失败')
  return { code: 0, message: 'ok', data }
}

export async function getUsageRecords(range: UsageTimeRange, page: number, pageSize: number): Promise<ApiResponse<UsageRecordsResponse>> {
  const data = await request<UsageRecordsResponse>(
    `/api/usage/records?${query(range)}&page=${page}&page_size=${pageSize}`,
    '请求记录加载失败',
  )
  return { code: 0, message: 'ok', data }
}
