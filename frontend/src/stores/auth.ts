import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchCurrentUser, login as loginApi, logout as logoutApi } from '@/api/auth'
import { tokenStorage } from '@/api/http'
import type { UserVO } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserVO | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => Boolean(tokenStorage.accessToken()))
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      const result = await loginApi({ username, password })
      tokenStorage.save(result.accessToken, result.refreshToken)
      user.value = result.user
    } finally {
      loading.value = false
    }
  }

  /**
   * 刷新页面后凭 token 恢复用户信息。
   * 失败不抛错——token 失效就当作未登录，由路由守卫送到登录页。
   */
  async function restore(): Promise<void> {
    if (!isLoggedIn.value || user.value) {
      return
    }

    try {
      user.value = await fetchCurrentUser()
    } catch {
      tokenStorage.clear()
      user.value = null
    }
  }

  async function logout(): Promise<void> {
    const refreshToken = tokenStorage.refreshToken()

    try {
      await logoutApi(refreshToken)
    } catch {
      // 登出接口是幂等的，即使失败也要清掉本地状态，
      // 否则用户会卡在「点了登出但还在登录态」的矛盾里
    } finally {
      tokenStorage.clear()
      user.value = null
    }
  }

  return { user, loading, isLoggedIn, displayName, login, restore, logout }
})
