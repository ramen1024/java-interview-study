import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import type { ApiResult } from '@/types'

/** 业务码：登录态失效。拦截器据此触发静默续期。 */
const CODE_UNAUTHORIZED = 401
/** 凭证过期，服务端用它区分「access token 过期」与「refresh token 也失效了」。 */
const CODE_TOKEN_INVALID = 1004

const ACCESS_TOKEN_KEY = 'jis.accessToken'
const REFRESH_TOKEN_KEY = 'jis.refreshToken'

export const tokenStorage = {
  accessToken: (): string | null => localStorage.getItem(ACCESS_TOKEN_KEY),
  refreshToken: (): string | null => localStorage.getItem(REFRESH_TOKEN_KEY),
  save(accessToken: string, refreshToken: string): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  },
  clear(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  },
}

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStorage.accessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

/**
 * 续期请求共用同一个 Promise。
 *
 * 首屏常常并发多个请求，若 access token 恰好在此时过期，会同时收到多个 401。
 * 不做单飞的话每个 401 都会发一次 refresh，而后端每次刷新都会轮换
 * refresh token，后发的请求会拿着已失效的旧 token 去换，最终把用户踢下线。
 */
let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = tokenStorage.refreshToken()
  if (!refreshToken) {
    throw new Error('没有 refresh token')
  }

  // 用裸 axios 而不是 http 实例，避免续期请求自己又被拦截器处理一遍
  const response = await axios.post<ApiResult<{ accessToken: string; refreshToken: string }>>(
    '/api/auth/refresh',
    { refreshToken },
  )

  if (response.data.code !== 0) {
    throw new Error(response.data.message)
  }

  const { accessToken, refreshToken: newRefreshToken } = response.data.data
  tokenStorage.save(accessToken, newRefreshToken)

  return accessToken
}

/** 续期失败后的统一处理：清凭证并跳登录。 */
function forceLogout(): void {
  tokenStorage.clear()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult<unknown>>) => {
    const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined
    const body = error.response?.data
    const isAuthFailure =
      body?.code === CODE_UNAUTHORIZED || body?.code === CODE_TOKEN_INVALID

    // 已经重试过就不再重试，否则续期失败时会变成死循环
    if (isAuthFailure && original && !original._retried) {
      original._retried = true

      try {
        refreshPromise = refreshPromise ?? refreshAccessToken().finally(() => {
          refreshPromise = null
        })

        const accessToken = await refreshPromise
        original.headers = { ...original.headers, Authorization: `Bearer ${accessToken}` }

        return await http.request(original)
      } catch {
        forceLogout()
        return Promise.reject(error)
      }
    }

    if (isAuthFailure) {
      forceLogout()
    }

    return Promise.reject(error)
  },
)

/**
 * 业务请求入口。把 {@code {code, message, data}} 拆开，
 * 调用方直接拿 data，非 0 一律抛错并弹出提示。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request<ApiResult<T>>(config)
    const body = response.data

    if (body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      throw new Error(body.message)
    }

    return body.data
  } catch (error) {
    const axiosError = error as AxiosError<ApiResult<unknown>>

    // 401 已经在拦截器里处理并跳转了，这里不再重复弹提示
    const code = axiosError.response?.data?.code
    if (code !== CODE_UNAUTHORIZED && code !== CODE_TOKEN_INVALID) {
      const message =
        axiosError.response?.data?.message ??
        (axiosError.code === 'ECONNABORTED' ? '请求超时，请检查后端是否已启动' : '网络异常，请稍后重试')
      ElMessage.error(message)
    }

    throw error
  }
}

/**
 * 静默请求：失败时不弹提示，由调用方自行处理。
 * 用于「有没有都行」的探测型请求。
 */
export async function requestSilently<T>(config: AxiosRequestConfig): Promise<T | null> {
  try {
    return await request<T>(config)
  } catch {
    return null
  }
}
