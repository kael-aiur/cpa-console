import type { ApiResponse } from '@/types/app'
import type { AvailableModelListResponse } from '@/types/models'

async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body.message) return new Error(body.message)
  } catch {
    // Use the fallback for non-JSON responses.
  }
  if (response.status === 401) return new Error('登录状态已失效，请重新登录')
  if (response.status === 403) return new Error('没有权限执行此操作')
  return new Error(fallback)
}

export async function getAvailableModels(): Promise<ApiResponse<AvailableModelListResponse>> {
  const response = await fetch('/api/models', { credentials: 'include' })
  if (!response.ok) throw await readError(response, '可用模型加载失败')
  return { code: 0, message: 'ok', data: (await response.json()) as AvailableModelListResponse }
}

export async function getCodexModelCatalog(): Promise<unknown> {
  const response = await fetch('/api/codex/model_catalog', { credentials: 'include' })
  if (!response.ok) throw await readError(response, 'ChatGPT 模型列表下载失败')
  return await response.json()
}
