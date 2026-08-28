import type { AccountInfo, ApiResponse, AppInfo } from '@/types/app'
import {
  MOCK_ACCOUNT_NAME,
  MOCK_PROJECT_LOGO_BASE64,
  MOCK_PROJECT_NAME,
} from '@/mocks/appMockData'

const MOCK_DELAY = 180

function createResponse<T>(data: T, message = 'ok'): ApiResponse<T> {
  return {
    code: 0,
    message,
    data,
  }
}

function delay<T>(data: T, message?: string): Promise<ApiResponse<T>> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(createResponse(data, message)), MOCK_DELAY)
  })
}

export function getAppInfo(): Promise<ApiResponse<AppInfo>> {
  return delay({
    logoBase64: MOCK_PROJECT_LOGO_BASE64,
    projectName: MOCK_PROJECT_NAME,
  })
}

export function getCurrentAccount(): Promise<ApiResponse<AccountInfo>> {
  return delay({
    name: MOCK_ACCOUNT_NAME,
  })
}

export function logout(): Promise<ApiResponse<null>> {
  return delay(null, '已退出登录')
}
