import { request } from '@/api/http'
import type { LoginResponse, UserVO } from '@/types'

export function register(payload: {
  username: string
  password: string
  nickname?: string
}): Promise<UserVO> {
  return request<UserVO>({
    url: '/auth/register',
    method: 'post',
    data: payload,
  })
}

export function login(payload: { username: string; password: string }): Promise<LoginResponse> {
  return request<LoginResponse>({
    url: '/auth/login',
    method: 'post',
    data: payload,
  })
}

export function logout(refreshToken: string | null): Promise<void> {
  return request<void>({
    url: '/auth/logout',
    method: 'post',
    data: { refreshToken: refreshToken ?? '' },
  })
}

export function fetchCurrentUser(): Promise<UserVO> {
  return request<UserVO>({
    url: '/auth/me',
    method: 'get',
  })
}
