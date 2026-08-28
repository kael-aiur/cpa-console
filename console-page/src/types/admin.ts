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
