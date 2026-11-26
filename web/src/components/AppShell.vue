<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/useNow'
import Avatar from './Avatar.vue'
import Icon from './Icon.vue'
import LogoMark from './LogoMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const now = useNow()

const NAV = [
  { label: '地图', to: '/', names: ['map', 'history'], icon: 'map' },
  { label: '家庭', to: '/family', names: ['family'], icon: 'users' },
  { label: '设备', to: '/devices', names: ['devices'], icon: 'device' },
  { label: '设置', to: '/settings', names: ['settings'], icon: 'settings' },
]

const menuOpen = ref(false)
const refreshBusy = ref(false)
const menuRef = ref<HTMLElement | null>(null)

const isMap = computed(() => route.name === 'map')
const onlineLabel = computed(() => {
  if (!family.totalMembers) return ''
  return `${family.onlineCount} / ${family.totalMembers} 人在线`
})
const syncLabel = computed(() => {
  if (!family.lastSyncedAt) return ''
  const seconds = Math.max(0, Math.round((now.value - family.lastSyncedAt) / 1000))
  if (seconds < 10) return '刚刚同步'
  if (seconds < 60) return `${seconds} 秒前同步`
  return `${Math.floor(seconds / 60)} 分钟前同步`
})
const roleLabel = computed(() => (family.myRole === 'admin' ? '管理员' : '成员'))

function isActive(names: string[]): boolean {
  return names.includes(String(route.name))
}

function closeMenu(): void {
  menuOpen.value = false
}

async function refresh(): Promise<void> {
  if (refreshBusy.value) return
  refreshBusy.value = true
  const [latestOk, rosterOk] = await Promise.all([
    family.refreshLatest(),
    family.refreshRoster(),
  ])
  refreshBusy.value = false
  if (latestOk && rosterOk) ui.toast('已获取全家最新位置', 'success')
  else {
    ui.toast(
      family.latestError ?? family.rosterError ?? '刷新失败，请稍后重试',
      'error',
    )
  }
}

function logout(): void {
  closeMenu()
  auth.logout()
  router.push({ name: 'login' })
}

function onDocumentClick(event: MouseEvent): void {
  if (!menuOpen.value) return
  if (menuRef.value && !menuRef.value.contains(event.target as Node)) closeMenu()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeMenu()
}

watch(
  () => route.fullPath,
  () => closeMenu(),
)

onMounted(() => {
  ui.initNetwork()
  if (auth.isAuthenticated && auth.familyId && !family.family) {
    void family.ensureLoaded()
  }
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar__inner">
        <RouterLink to="/" class="brand" aria-label="你在哪 · 返回家庭地图">
          <LogoMark :size="30" wordmark />
        </RouterLink>

        <nav class="nav" aria-label="主导航">
          <RouterLink
            v-for="item in NAV"
            :key="item.to"
            :to="item.to"
            class="nav__item"
            :class="{ 'is-active': isActive(item.names) }"
            :aria-current="isActive(item.names) ? 'page' : undefined"
            :title="item.label"
          >
            <Icon :name="item.icon" :size="18" />
            <span class="nav__label">{{ item.label }}</span>
          </RouterLink>
        </nav>

        <div class="topbar__right">
          <RouterLink
            v-if="family.family"
            to="/family"
            class="family-chip"
            :title="`${family.familyName} · ${onlineLabel}`"
          >
            <span class="family-chip__name">{{ family.familyName }}</span>
            <span class="family-chip__sep" aria-hidden="true" />
            <span class="family-chip__online">
              <span class="status-dot" :class="family.onlineCount ? 'status-dot--online' : ''" />
              {{ onlineLabel || '等待同步' }}
            </span>
          </RouterLink>

          <button
            v-if="isMap"
            type="button"
            class="btn btn--icon btn--ghost refresh-btn"
            :disabled="refreshBusy"
            :aria-label="refreshBusy ? '正在刷新位置' : '刷新位置'"
            :title="syncLabel || '刷新位置'"
            @click="refresh"
          >
            <span class="refresh-icon" :class="{ 'is-spinning': refreshBusy }">
              <Icon name="refresh" :size="18" />
            </span>
          </button>

          <div ref="menuRef" class="user">
            <button
              type="button"
              class="user__button"
              :aria-expanded="menuOpen"
              aria-haspopup="menu"
              aria-label="打开用户菜单"
              @click="menuOpen = !menuOpen"
            >
              <Avatar
                :name="auth.user?.nickname ?? '我'"
                :id="auth.user?.id"
                :size="32"
              />
              <Icon name="chevron-down" :size="14" class="user__chevron" />
            </button>

            <Transition name="menu">
              <div v-if="menuOpen" class="user-menu" role="menu">
                <div class="user-menu__head">
                  <strong>{{ auth.user?.nickname ?? '家人' }}</strong>
                  <span>
                    @{{ auth.user?.username ?? '—' }}
                    <template v-if="family.family"> · {{ roleLabel }}</template>
                  </span>
                </div>
                <RouterLink to="/settings" role="menuitem" @click="closeMenu">
                  <Icon name="settings" :size="17" />
                  账号与设置
                </RouterLink>
                <RouterLink to="/family" role="menuitem" @click="closeMenu">
                  <Icon name="users" :size="17" />
                  家庭管理
                </RouterLink>
                <button type="button" class="user-menu__logout" role="menuitem" @click="logout">
                  <Icon name="logout" :size="17" />
                  退出登录
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </div>
    </header>

    <div v-if="!ui.online" class="net-banner" role="status">
      <Icon name="wifi-off" :size="16" />
      网络已断开 · 正在展示最近一次同步的数据
    </div>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--white);
}

.app-main {
  flex: 1;
  min-width: 0;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 600;
  height: var(--topbar-h);
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--ring);
}

.topbar__inner {
  height: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.brand {
  display: inline-flex;
  align-items: center;
  border-radius: 10px;
  flex: 0 0 auto;
}

.nav {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav__item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 38px;
  padding: 0 12px;
  border-radius: 8px;
  color: var(--slate);
  font-size: 14px;
  font-weight: 500;
  letter-spacing: -0.01em;
  transition:
    background-color 0.16s var(--ease-out),
    color 0.16s var(--ease-out);
}

.nav__item:hover {
  background: var(--surface-soft);
  color: var(--ink);
}

.nav__item.is-active {
  background: var(--blue-soft);
  color: var(--blue-pressed);
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.family-chip {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 13px;
  border-radius: var(--r-pill);
  box-shadow: inset 0 0 0 1px var(--ring);
  font-size: 12.5px;
  white-space: nowrap;
  transition: background-color 0.16s;
}

.family-chip:hover {
  background: var(--surface-soft);
}

.family-chip__name {
  font-weight: 600;
  color: var(--ink);
}

.family-chip__sep {
  width: 1px;
  height: 14px;
  background: var(--ring);
}

.family-chip__online {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--ink-muted);
}

.refresh-btn {
  color: var(--slate);
}

.refresh-icon {
  display: inline-flex;
}

.refresh-icon.is-spinning {
  animation: spin 0.8s linear infinite;
}

.user {
  position: relative;
}

.user__button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 5px 3px 3px;
  border-radius: var(--r-pill);
  border: 1px solid transparent;
  transition:
    border-color 0.16s,
    background-color 0.16s;
}

.user__button:hover {
  border-color: var(--ring);
  background: var(--white);
}

.user__chevron {
  color: var(--ink-muted);
}

.user-menu {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 700;
  width: 238px;
  padding: 8px;
  border-radius: 14px;
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
}

.user-menu__head {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px 12px;
  margin-bottom: 6px;
  border-bottom: 1px solid var(--line);
}

.user-menu__head strong {
  font-size: 15px;
  letter-spacing: -0.01em;
}

.user-menu__head span {
  font-size: 12px;
  color: var(--ink-muted);
}

.user-menu a,
.user-menu__logout {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border-radius: 8px;
  color: var(--slate);
  font-size: 14px;
  text-align: left;
  transition: background-color 0.14s;
}

.user-menu a:hover,
.user-menu__logout:hover {
  background: var(--surface-soft);
  color: var(--ink);
}

.user-menu__logout {
  color: var(--danger);
  margin-top: 2px;
}

.user-menu__logout:hover {
  background: #fdf4f4;
  color: var(--danger);
}

.menu-enter-active,
.menu-leave-active {
  transition: opacity 0.16s ease, transform 0.18s var(--ease-out);
}

.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}

.net-banner {
  position: fixed;
  top: calc(var(--topbar-h) + 12px);
  left: 50%;
  transform: translateX(-50%);
  z-index: 800;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--r-pill);
  background: var(--orange);
  color: var(--orange-ink);
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 0 0 1px rgba(122, 67, 17, 0.08);
  white-space: nowrap;
  animation: banner-in 0.24s var(--ease-out);
}

@keyframes banner-in {
  from {
    opacity: 0;
    transform: translate(-50%, -4px);
  }
  to {
    opacity: 1;
    transform: translate(-50%, 0);
  }
}

@media (max-width: 1100px) {
  .topbar__inner {
    gap: 16px;
    padding: 0 16px;
  }

  .nav__label {
    display: none;
  }

  .nav__item {
    width: 38px;
    justify-content: center;
    padding: 0;
  }

  .family-chip {
    display: none;
  }
}

@media (max-width: 640px) {
  .topbar__inner {
    gap: 10px;
    padding: 0 14px;
  }

  .topbar__right {
    gap: 4px;
  }

  .user__chevron {
    display: none;
  }

  .refresh-btn {
    width: 36px;
    height: 36px;
    min-height: 36px;
  }

  .user-menu {
    width: 220px;
  }
}

@media (max-width: 480px) {
  .brand :deep(.logo-word) {
    display: none;
  }

  .topbar__inner {
    gap: 8px;
  }

  .nav {
    gap: 0;
  }

  .nav__item {
    width: 35px;
  }

  .net-banner {
    top: calc(var(--topbar-h) + 8px);
    max-width: calc(100vw - 24px);
    white-space: normal;
    text-align: center;
    padding: 8px 14px;
  }
}
</style>
