import type { ApiResponse } from '@/types/app'
import type { AccountQuota, QuotaEndpointResponse, QuotaFile, QuotaFileListResponse, QuotaWindow } from '@/types/quota'

interface BackendProviderListResponse { providers: QuotaFile[] }
interface BackendQuotaResponse { quota: Record<string, unknown> }

async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = (await response.json()) as { message?: string; error?: string }
    if (body.message || body.error) return new Error(body.message ?? body.error)
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

function normalizeProvider(provider: QuotaFile): QuotaFile {
  return {
    ...provider,
    id: provider.id || provider.auth_index,
    auth_index: provider.auth_index || provider.id,
    tags: provider.tags ?? [],
    recent_requests: provider.recent_requests ?? [],
    success: provider.success ?? 0,
    failed: provider.failed ?? 0,
    email: provider.email ?? provider.account ?? '',
    label: provider.label ?? provider.name,
    provider: provider.provider ?? provider.type ?? 'unknown',
    type: provider.type ?? provider.provider ?? 'unknown',
    status: provider.status ?? (provider.disabled ? 'error' : 'active'),
    disabled: provider.disabled ?? false,
    unavailable: provider.unavailable ?? false,
    runtime_only: provider.runtime_only ?? false,
    account: provider.account ?? '',
    account_type: provider.account_type ?? '',
    size: provider.size ?? 0,
    source: provider.source ?? 'cpa',
  }
}

function normalizeQuota(raw: Record<string, unknown>, provider: QuotaFile): AccountQuota {
  const windows = Array.isArray(raw.windows) ? raw.windows as QuotaWindow[] : []
  return {
    provider: typeof raw.provider === 'string' ? raw.provider : provider.provider,
    tierName: typeof raw.tierName === 'string' ? raw.tierName : provider.provider,
    windows,
  }
}

export async function getQuotaFiles(): Promise<ApiResponse<QuotaFileListResponse>> {
  const response = await request<BackendProviderListResponse>('/api/quota/providers', '供应商列表加载失败')
  return { code: 0, message: 'ok', data: { files: (response.providers ?? []).map(normalizeProvider) } }
}

export async function getQuotaForFile(file: QuotaFile): Promise<QuotaEndpointResponse> {
  const referenceId = file.auth_index || file.id
  const response = await request<BackendQuotaResponse>(
    `/api/quota/providers/${encodeURIComponent(referenceId)}/quota`,
    `额度获取失败：${file.name}`,
  )
  return {
    status_code: 200,
    header: {},
    body: JSON.stringify(response.quota ?? {}),
    quota: normalizeQuota(response.quota ?? {}, file),
  }
}
