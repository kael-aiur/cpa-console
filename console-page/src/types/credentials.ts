export type CredentialType = 'auth_file' | 'apikey'

export interface AdminCredential {
  id: number
  name: string
  credential_type: CredentialType
  reference_id: string
  enabled: boolean
  tags: string[]
}

export interface AdminCredentialListResponse {
  credentials: AdminCredential[]
  total: number
}
