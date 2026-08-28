export interface AppInfo {
  logoBase64: string
  projectName: string
}

export interface AccountInfo {
  name: string
}

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}
