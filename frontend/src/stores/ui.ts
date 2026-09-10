import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const THEME_KEY = 'jis.theme'
const SIDEBAR_KEY = 'jis.sidebarCollapsed'

export type ThemeMode = 'light' | 'dark'

export const useUiStore = defineStore('ui', () => {
  const theme = ref<ThemeMode>(readTheme())
  const sidebarCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === '1')
  const searchVisible = ref(false)

  function readTheme(): ThemeMode {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved === 'light' || saved === 'dark') {
      return saved
    }

    // 首次访问跟随系统，之后以用户选择为准
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }

  function applyTheme(): void {
    // Element Plus 用 html 上的 dark 类切换暗色变量，自己的样式也复用它
    document.documentElement.classList.toggle('dark', theme.value === 'dark')
    localStorage.setItem(THEME_KEY, theme.value)
  }

  function toggleTheme(): void {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem(SIDEBAR_KEY, sidebarCollapsed.value ? '1' : '0')
  }

  function openSearch(): void {
    searchVisible.value = true
  }

  function closeSearch(): void {
    searchVisible.value = false
  }

  watch(theme, applyTheme, { immediate: true })

  return {
    theme,
    sidebarCollapsed,
    searchVisible,
    toggleTheme,
    toggleSidebar,
    openSearch,
    closeSearch,
  }
})
