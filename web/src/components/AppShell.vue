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
        <div class="brand-group">
          <RouterLink to="/" class="brand" aria-label="你在哪 · 返回家庭地图">
            <LogoMark :size="30" wordmark />
          </RouterLink>

          <RouterLink
            v-if="family.family"
            to="/family"
            class="family-chip"
            :title="`${family.familyName} · ${onlineLabel}`"
          >
            <span class="family-chip__name">{{ family.familyName }}</span>
            <Icon name="chevron-down" :size="12" class="family-chip__chevron" />
          </RouterLink>

          <span v-if="family.family" class="online-status">
            <span class="status-dot" :class="family.onlineCount ? 'status-dot--online' : ''" />
            {{ onlineLabel || '等待同步' }}
          </span>
        </div>

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
            <Icon :name="item.icon" :size="17" />
            <span class="nav__label">{{ item.label }}</span>
          </RouterLink>
        </nav>

        <div class="topbar__right">
          <button
            v-if="isMap"
            type="button"
            class="refresh-btn"
            :disabled="refreshBusy"
            :aria-label="refreshBusy ? '正在刷新位置' : '刷新位置'"
            :title="syncLabel || '刷新位置'"
            @click="refresh"
          >
            <span class="refresh-icon" :class="{ 'is-spinning': refreshBusy }">
              <Icon name="refresh" :size="15" />
            </span>
          </button>

          <span v-if="isMap && syncLabel" class="sync-hint">{{ syncLabel }}</span>

          <span class="topbar__divider" aria-hidden="true" />

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
                :size="30"
              />
              <span class="user__name">{{ auth.user?.nickname ?? '我' }}</span>
              <Icon name="chevron-down" :size="13" class="user__chevron" />
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
  background: var(--canvas);
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
  background: var(--white);
  border-bottom: 1px solid var(--input-line);
}

.topbar__inner {
  height: 100%;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 28px;
}

.brand-group {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
}

.brand {
  display: inline-flex;
  align-items: center;
  border-radius: 8px;
  flex: 0 0 auto;
}

.family-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 10px;
  border-radius: 8px;
  background: var(--surface-soft);
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  transition: background-color 0.16s;
}

.family-chip:hover {
  background: var(--surface-sunken);
}

.family-chip__chevron {
  color: var(--slate);
}

.online-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--slate);
  font-size: 11px;
  white-space: nowrap;
}

.online-status .status-dot {
  width: 6px;
  height: 6px;
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.nav__item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 14px;
  border-radius: 10px;
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
  color: var(--blue);
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--white);
  box-shadow: inset 0 0 0 1px var(--input-line);
  color: var(--ink);
  transition: background-color 0.16s;
}

.refresh-btn:hover:not(:disabled) {
  background: var(--surface-soft);
}

.refresh-btn:disabled {
  opacity: 0.6;
}

.refresh-icon {
  display: inline-flex;
}

.refresh-icon.is-spinning {
  animation: spin 0.8s linear infinite;
}

.sync-hint {
  color: var(--ink-muted);
  font-size: 10.5px;
  white-space: nowrap;
}

.topbar__divider {
  width: 1px;
  height: 24px;
  background: var(--input-line);
}

.user {
  position: relative;
}

.user__button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 4px 0 0;
  border-radius: var(--r-pill);
  transition: opacity 0.16s;
}

.user__button:hover {
  opacity: 0.78;
}

.user__name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
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

  .sync-hint,
  .topbar__divider,
  .user__name {
    display: none;
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

  .online-status {
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
