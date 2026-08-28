export type UserRole = 'user' | 'admin'

export interface UserInfo {
  nickname: string
  role: UserRole
  user_id: number
}
