export type CredentialType = 'auth_file' | 'apikey'

export interface AdminCredential {
  id: number
  name: string
  credential_type: CredentialType
  provider: string
  reference_id: string
  enabled: boolean
  tags: string[]
}

export interface AdminCredentialListResponse {
  credentials: AdminCredential[]
  total: number
}

export interface AdminCredentialResetResponse {
  status: 'ok' | 'partial_success' | 'error'
  quota_reset: 'completed' | 'failed' | 'unknown'
  cooldown_reset: 'completed' | 'failed' | 'unknown' | 'not_attempted'
  message: string
}
