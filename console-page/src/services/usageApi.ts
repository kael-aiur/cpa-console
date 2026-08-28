import type { ApiResponse } from '@/types/app'
import type { UsageRecordsResponse, UsageSummary, UsageTimeRange } from '@/types/usage'

const MOCK_DELAY = 280
const MOCK_MODELS = ['gpt-5', 'claude-sonnet-4', 'gemini-2.5-pro', 'grok-4']
const BASE_TIME = new Date('2026-08-27T20:40:00+08:00').getTime()

function delay<T>(data: T): Promise<ApiResponse<T>> {
  return new Promise((resolve) => window.setTimeout(() => resolve({ code: 0, message: 'ok', data }), MOCK_DELAY))
}

function buildRecords(): UsageRecordsResponse['records'] {
  return Array.from({ length: 37 }, (_, index) => {
    const model = MOCK_MODELS[index % MOCK_MODELS.length]
    const input = 620 + (index * 173) % 2800
    const output = 180 + (index * 97) % 1400
    const cached = index % 3 === 0 ? Math.round(input * 0.35) : index % 3 === 1 ? 0 : Math.round(input * 0.12)
    const success = index % 9 !== 4
    return {
      id: 1000 + index,
      model,
      input_tokens: input,
      output_tokens: output,
      cached_tokens: cached,
      request_time: new Date(BASE_TIME - index * 23 * 60 * 1000).toISOString(),
      duration_ms: 420 + (index * 137) % 2200,
      status_code: success ? 200 : index % 2 === 0 ? 429 : 500,
      success,
    }
  })
}

const ALL_RECORDS = buildRecords()

export function getUsageSummary(_range: UsageTimeRange): Promise<ApiResponse<UsageSummary>> {
  const modelTokenDistribution: Record<string, number> = {}
  const modelRequestDistribution: Record<string, number> = {}
  for (const record of ALL_RECORDS) {
    modelTokenDistribution[record.model] = (modelTokenDistribution[record.model] ?? 0) + record.input_tokens + record.output_tokens
    modelRequestDistribution[record.model] = (modelRequestDistribution[record.model] ?? 0) + 1
  }
  return delay({
    total_requests: ALL_RECORDS.length,
    total_tokens: ALL_RECORDS.reduce((sum, item) => sum + item.input_tokens + item.output_tokens, 0),
    average_duration_ms: Math.round(ALL_RECORDS.reduce((sum, item) => sum + item.duration_ms, 0) / ALL_RECORDS.length),
    model_token_distribution: modelTokenDistribution,
    model_request_distribution: modelRequestDistribution,
  })
}

export function getUsageRecords(range: UsageTimeRange, page: number, pageSize: number): Promise<ApiResponse<UsageRecordsResponse>> {
  void range
  const totalPages = Math.ceil(ALL_RECORDS.length / pageSize)
  const safePage = Math.max(1, Math.min(page, totalPages))
  const start = (safePage - 1) * pageSize
  return delay({ records: ALL_RECORDS.slice(start, start + pageSize), page: safePage, page_size: pageSize, total: ALL_RECORDS.length, total_pages: totalPages })
}
