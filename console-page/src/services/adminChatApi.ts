import type { ApiResponse } from '@/types/app'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ResponsesStreamEvent {
  type?: string
  delta?: string
  response?: { error?: { message?: string } }
  error?: { message?: string }
}

interface CsrfResponse { token: string }

async function readError(response: Response, fallback: string): Promise<Error> {
  const raw = await response.text()
  try {
    const body = JSON.parse(raw) as { message?: string; error?: { message?: string } }
    if (body.message) return new Error(body.message)
    if (body.error?.message) return new Error(body.error.message)
  } catch {
    const dataLine = raw.split(/\r?\n/).find((line) => line.startsWith('data:'))
    if (dataLine) {
      try {
        const event = JSON.parse(dataLine.slice(5).trim()) as ResponsesStreamEvent
        const message = event.error?.message || event.response?.error?.message
        if (message) return new Error(message)
      } catch {
        // Fall through to the HTTP status fallback.
      }
    }
  }
  if (response.status === 401) return new Error('登录状态已失效，请重新登录')
  if (response.status === 403) return new Error('没有权限执行此操作')
  return new Error(fallback)
}

async function getCsrfToken(): Promise<string> {
  const response = await fetch('/api/csrf', { credentials: 'include' })
  if (!response.ok) throw await readError(response, '获取安全令牌失败，请重试')
  return ((await response.json()) as CsrfResponse).token
}

export async function streamAdminResponse(
  model: string,
  input: string,
  onEvent: (eventType: string, event: ResponsesStreamEvent) => void,
): Promise<ApiResponse<null>> {
  const token = await getCsrfToken()
  const response = await fetch('/admin/v1/responses', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', 'X-XSRF-TOKEN': token },
    body: JSON.stringify({
      model,
      input: [{ type: 'message', role: 'user', content: [{ type: 'input_text', text: input }] }],
      stream: true,
    }),
  })
  if (!response.ok) throw await readError(response, '模型响应失败，请稍后重试')
  if (!response.body) throw new Error('模型响应流不可用')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventType = 'message'
  let dataLines: string[] = []

  const dispatch = () => {
    if (dataLines.length === 0) return
    const rawData = dataLines.join('\n')
    dataLines = []
    const data = rawData.trim()
    if (!data || data === '[DONE]') return
    try {
      onEvent(eventType, JSON.parse(data) as ResponsesStreamEvent)
    } catch {
      throw new Error('模型响应事件格式无效')
    } finally {
      eventType = 'message'
    }
  }

  const consumeLine = (line: string) => {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim() || 'message'
      return
    }
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }

  try {
    while (true) {
      const result = await reader.read()
      buffer += decoder.decode(result.value ?? new Uint8Array(), { stream: !result.done })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) consumeLine(line)
      if (result.done) break
    }
    if (buffer) consumeLine(buffer)
    dispatch()
  } finally {
    reader.releaseLock()
  }
  return { code: 0, message: 'ok', data: null }
}
