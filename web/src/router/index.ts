import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '@/stores'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiresFamily?: boolean
    guestOnly?: boolean
    bare?: boolean
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path === from.path) return {}
    return { top: 0 }
  },
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { bare: true, guestOnly: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { bare: true, guestOnly: true, title: '注册' },
    },
    {
      path: '/onboarding',
      name: 'onboarding',
      component: () => import('@/views/OnboardingView.vue'),
      meta: { bare: true, requiresAuth: true, title: '加入家庭' },
    },
    {
      path: '/',
      name: 'map',
      component: () => import('@/views/MapView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '家庭地图' },
    },
    {
      path: '/history/:deviceId',
      name: 'history',
      component: () => import('@/views/HistoryView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '历史轨迹' },
    },
    {
      path: '/alerts',
      name: 'alerts',
      component: () => import('@/views/AlertsView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '消息' },
    },
    {
      path: '/family',
      name: 'family',
      component: () => import('@/views/FamilyView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '家庭' },
    },
    {
      path: '/devices',
      name: 'devices',
      component: () => import('@/views/DevicesView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '设备' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { requiresAuth: true, requiresFamily: true, title: '设置' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { requiresAuth: true, title: '页面不存在' },
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  await auth.ensureSession()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.meta.guestOnly && auth.isAuthenticated) {
    return { name: 'map' }
  }

  if (to.meta.requiresFamily && auth.initialized && !auth.familyId) {
    return { name: 'onboarding' }
  }

  if (to.name === 'onboarding' && auth.familyId) {
    return { name: 'map' }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 你在哪` : '你在哪 · 家庭位置共享'
})

export default router
