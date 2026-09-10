import { createRouter, createWebHistory } from 'vue-router'

import { tokenStorage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '仪表盘' },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/views/KnowledgeView.vue'),
          meta: { title: '知识体系' },
        },
        {
          path: 'knowledge/:slug',
          name: 'card-detail',
          component: () => import('@/views/CardDetailView.vue'),
          meta: { title: '知识点' },
        },
        {
          path: 'review',
          name: 'review',
          component: () => import('@/views/ReviewView.vue'),
          meta: { title: '今日复习' },
        },
        {
          path: 'quiz',
          name: 'quiz',
          component: () => import('@/views/QuizView.vue'),
          meta: { title: '自测' },
        },
        {
          path: 'quiz/wrong',
          name: 'wrong-book',
          component: () => import('@/views/WrongBookView.vue'),
          meta: { title: '错题本' },
        },
        {
          path: 'favorites',
          name: 'favorites',
          component: () => import('@/views/FavoritesView.vue'),
          meta: { title: '我的收藏' },
        },
        {
          path: 'search',
          name: 'search',
          component: () => import('@/views/SearchView.vue'),
          meta: { title: '搜索' },
        },
        {
          path: 'stats',
          name: 'stats',
          component: () => import('@/views/StatsView.vue'),
          meta: { title: '学习统计' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '设置' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const hasToken = Boolean(tokenStorage.accessToken())

  if (to.meta.public) {
    // 已登录还去登录页就直接回首页，避免出现两个登录态
    return hasToken && to.name === 'login' ? { name: 'dashboard' } : true
  }

  if (!hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 刷新页面后补回用户信息，顺带校验 token 是否还有效
  const auth = useAuthStore()
  await auth.restore()

  return auth.user ? true : { name: 'login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Java 面试学习站` : 'Java 面试学习站'
})

export default router
