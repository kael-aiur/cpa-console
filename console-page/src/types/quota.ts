export interface RecentRequestBucket {
  time: string
  success: number
  failed: number
}

export interface CodexIdToken {
  chatgpt_account_id?: string
  chatgpt_subscription_active_start?: string
  chatgpt_subscription_active_until?: string
  plan_type?: string
}

export interface QuotaFile {
  account: string
  account_type: string
  auth_index: string
  created_at?: string
  disabled: boolean
  email: string
  failed: number
  id: string
  id_token?: CodexIdToken
  label: string
  last_refresh?: string
  modtime?: string
  name: string
  path: string
  project_id?: string
  provider: string
  recent_requests: RecentRequestBucket[]
  runtime_only: boolean
  size: number
  source: string
  status: 'active' | 'error' | string
  status_message?: string
  success: number
  tags: string[]
  type: string
  unavailable: boolean
  updated_at?: string
}

export interface QuotaFileListResponse {
  files: QuotaFile[]
}

export interface QuotaWindow {
  label: string
  remainingPercent: number
  resetAt: string
}

export interface AccountQuota {
  provider: string
  tierName: string
  windows: QuotaWindow[]
}

export interface QuotaEndpointResponse {
  status_code: number
  header: Record<string, string[]>
  body: string
  quota: AccountQuota
}
