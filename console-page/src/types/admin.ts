export type AdminUserRole = 'admin' | 'user'

export interface AdminUser {
  user_id: number
  nickname: string
  role: AdminUserRole
  api_key: string
  created_at: string
}

export interface AdminUserListResponse {
  users: AdminUser[]
  total: number
}

export interface AdminAvailableModel {
  id: number
  name: string
  owned_by: string
  litellm_model_id: string | null
  tags: string[]
}
export interface AdminAvailableModelListResponse { models: AdminAvailableModel[]; total: number }
export interface AdminLiteLlmMetadata { model_id: string; provider: string; mode: string; max_input_tokens: number | null; max_output_tokens: number | null; max_tokens: number | null; metadata_json: string; synced_at: string }
export interface AdminLiteLlmMetadataListResponse { models: AdminLiteLlmMetadata[]; total: number }
export interface AdminLiteLlmSyncConfig { url: string; proxy_enabled: boolean; proxy_host: string; proxy_port: number; updated_at: string }
