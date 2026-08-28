export interface UsageTimeRange {
  start: string
  end: string
}

export interface UsageRecord {
  id: number
  model: string
  input_tokens: number
  output_tokens: number
  cached_tokens: number
  request_time: string
  duration_ms: number
  status_code: number
  success: boolean
}

export interface UsageSummary {
  total_requests: number
  total_tokens: number
  average_duration_ms: number
  model_token_distribution: Record<string, number>
  model_request_distribution: Record<string, number>
}

export interface UsageRecordsResponse {
  records: UsageRecord[]
  page: number
  page_size: number
  total: number
  total_pages: number
}
