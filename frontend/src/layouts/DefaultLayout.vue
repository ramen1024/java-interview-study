<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchOverview } from '@/api/stats'
import GlobalSearch from '@/components/GlobalSearch.vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const dueCount = ref(0)

const navItems = [
  { name: 'dashboard', label: '仪表盘', icon: 'Odometer' },
  { name: 'knowledge', label: '知识体系', icon: 'Collection' },
  { name: 'review', label: '今日复习', icon: 'Refresh', badge: true },
  { name: 'quiz', label: '自测', icon: 'EditPen' },
  { name: 'wrong-book', label: '错题本', icon: 'WarningFilled' },
  { name: 'favorites', label: '我的收藏', icon: 'Star' },
  { name: 'stats', label: '学习统计', icon: 'DataLine' },
  { name: 'settings', label: '设置', icon: 'Setting' },
] as const

const activeNav = computed(() => {
  // 卡片详情、搜索结果都归属于它们来处的导航项，避免侧边栏失去高亮
  if (route.name === 'card-detail') {
    return 'knowledge'
  }
  if (route.name === 'search') {
    return 'knowledge'
  }

  return route.name as string
})

const pageTitle = computed(() => (route.meta.title as string | undefined) ?? '')

async function loadOverview(): Promise<void> {
  try {
    const overview = await fetchOverview()
    dueCount.value = overview.dueCount
  } catch {
    // 概览只是导航上的一个徽标，取不到就不显示，不打扰用户
  }
}

function handleShortcut(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    ui.openSearch()
  }
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  await auth.logout()
  await router.push({ name: 'login' })
}

onMounted(() => {
  window.addEventListener('keydown', handleShortcut)
  void loadOverview()
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleShortcut)
})

// 复习完一批题或重新导入内容后，徽标里的待复习数会变
watch(() => route.fullPath, () => void loadOverview())
</script>

<template>
  <div class="layout" :class="{ 'layout--collapsed': ui.sidebarCollapsed }">
    <aside class="sidebar">
      <div class="sidebar__brand" @click="router.push({ name: 'dashboard' })">
        <div class="sidebar__logo">J</div>
        <span v-show="!ui.sidebarCollapsed" class="sidebar__title">Java 面试学习站</span>
      </div>

      <nav class="sidebar__nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ 'nav-item--active': activeNav === item.name }"
        >
          <el-icon class="nav-item__icon">
            <component :is="item.icon" />
          </el-icon>
          <span v-show="!ui.sidebarCollapsed" class="nav-item__label">{{ item.label }}</span>
          <span
            v-if="'badge' in item && item.badge && dueCount > 0 && !ui.sidebarCollapsed"
            class="nav-item__badge"
          >
            {{ dueCount > 99 ? '99+' : dueCount }}
          </span>
        </RouterLink>
      </nav>

      <div class="sidebar__footer">
        <el-tooltip :content="ui.sidebarCollapsed ? '展开侧栏' : '收起侧栏'" placement="right">
          <button class="icon-button" type="button" @click="ui.toggleSidebar()">
            <el-icon>
              <component :is="ui.sidebarCollapsed ? 'Expand' : 'Fold'" />
            </el-icon>
          </button>
        </el-tooltip>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1 class="topbar__title">{{ pageTitle }}</h1>

        <div class="topbar__actions">
          <button class="search-trigger" type="button" @click="ui.openSearch()">
            <el-icon><Search /></el-icon>
            <span class="search-trigger__text">搜索知识点</span>
            <kbd class="search-trigger__kbd">Ctrl K</kbd>
          </button>

          <el-tooltip :content="ui.theme === 'dark' ? '切换到浅色' : '切换到深色'" placement="bottom">
            <button class="icon-button" type="button" @click="ui.toggleTheme()">
              <el-icon>
                <component :is="ui.theme === 'dark' ? 'Sunny' : 'Moon'" />
              </el-icon>
            </button>
          </el-tooltip>

          <el-dropdown trigger="click">
            <button class="user-button" type="button">
              <span class="user-button__avatar">{{ auth.displayName.charAt(0).toUpperCase() }}</span>
              <span class="user-button__name">{{ auth.displayName }}</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push({ name: 'settings' })">设置</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </RouterView>
      </main>
    </div>

    <GlobalSearch />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}

/* ------------------------------------------------------------ 侧边栏 */

.sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: 232px;
  height: 100vh;
  flex-shrink: 0;
  background: var(--jis-surface);
  border-right: 1px solid var(--jis-border);
  transition: width 0.18s ease;
}

.layout--collapsed .sidebar {
  width: 68px;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  cursor: pointer;
  user-select: none;
}

.sidebar__logo {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 9px;
  background: linear-gradient(135deg, var(--jis-accent), #a855f7);
  color: #fff;
  font-weight: 700;
  font-size: 16px;
}

.sidebar__title {
  font-size: 14px;
  font-weight: 650;
  white-space: nowrap;
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  overflow-y: auto;
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--jis-radius-sm);
  color: var(--jis-text-secondary);
  font-size: 14px;
  white-space: nowrap;
  transition: background 0.15s ease, color 0.15s ease;
}

.nav-item:hover {
  background: var(--jis-surface-muted);
  color: var(--jis-text);
}

.nav-item--active {
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-weight: 600;
}

.nav-item__icon {
  font-size: 17px;
  flex-shrink: 0;
}

.nav-item__label {
  flex: 1;
}

.nav-item__badge {
  min-width: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: var(--jis-accent);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}

.layout--collapsed .nav-item {
  justify-content: center;
  padding: 10px 0;
}

.sidebar__footer {
  padding: 10px;
  border-top: 1px solid var(--jis-border);
}

/* ------------------------------------------------------------ 顶栏 */

.main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 60px;
  padding: 0 24px;
  background: color-mix(in srgb, var(--jis-bg) 88%, transparent);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--jis-border);
}

.topbar__title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.topbar__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 7px 12px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface);
  color: var(--jis-text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}

.search-trigger:hover {
  border-color: var(--jis-accent);
  color: var(--jis-text-secondary);
}

.search-trigger__kbd {
  padding: 1px 6px;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
  background: var(--jis-surface-muted);
  font-size: 11px;
  color: var(--jis-text-muted);
}

.icon-button {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface);
  color: var(--jis-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}

.icon-button:hover {
  border-color: var(--jis-accent);
  color: var(--jis-accent);
}

.user-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border: 1px solid var(--jis-border);
  border-radius: 999px;
  background: var(--jis-surface);
  color: var(--jis-text);
  font-size: 13px;
  cursor: pointer;
}

.user-button__avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-weight: 650;
  font-size: 12px;
}

.content {
  flex: 1;
}

@media (max-width: 900px) {
  .sidebar {
    width: 68px;
  }

  .sidebar__title,
  .nav-item__label,
  .nav-item__badge {
    display: none !important;
  }

  .nav-item {
    justify-content: center;
    padding: 10px 0;
  }

  .search-trigger__text,
  .search-trigger__kbd,
  .user-button__name {
    display: none;
  }

  .topbar {
    padding: 0 14px;
  }
}
</style>
