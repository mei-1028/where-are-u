<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/useNow'
import Icon from '@/components/Icon.vue'
import MapCanvas from '@/components/MapCanvas.vue'
import MemberCard from '@/components/MemberCard.vue'
import MemberDrawer from '@/components/MemberDrawer.vue'
import StateBlock from '@/components/StateBlock.vue'

const POLL_INTERVAL = 10_000
const ROSTER_REFRESH_INTERVAL = 60_000
const VISIBLE_ROSTER_MIN_INTERVAL = 12_000

const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const now = useNow()

const selectedId = ref<string | null>(null)
const membersOpen = ref(false)
const locatingId = ref<string | null>(null)

let pollTimer: number | null = null

const members = computed(() => family.memberViews)
const selectedMember = computed(
  () => members.value.find((member) => member.userId === selectedId.value) ?? null,
)
const currentUserId = computed(() => auth.user?.id ?? null)
const loadingInitial = computed(() => !family.family && family.loading)
const fatalError = computed(() => !family.family && Boolean(family.error))
const hasPositions = computed(() => members.value.some((member) => member.lastLocation))
const showInviteEmpty = computed(
  () =>
    Boolean(family.family) &&
    !loadingInitial.value &&
    members.value.length === 1,
)
const waitingForPositions = computed(
  () =>
    Boolean(family.family) &&
    !loadingInitial.value &&
    members.value.length > 1 &&
    !hasPositions.value,
)
const connectionError = computed(
  () =>
    Boolean(family.family) &&
    Boolean(family.latestError || family.rosterError) &&
    ui.online &&
    !fatalError.value,
)
const connectionMessage = computed(() => {
  if (family.rosterError && !family.latestError) return '家庭成员同步中断，正在重试'
  return '连接中断，正在重试'
})
const onlineCount = computed(() => members.value.filter((member) => member.online).length)
const syncText = computed(() => {
  if (!family.lastSyncedAt) return family.latestLoading ? '正在同步' : '等待同步'
  const seconds = Math.max(0, Math.round((now.value - family.lastSyncedAt) / 1000))
  if (seconds < 12) return '刚刚同步'
  if (seconds < 60) return `${seconds} 秒前同步`
  return `${Math.floor(seconds / 60)} 分钟前同步`
})

function selectMember(userId: string | null): void {
  selectedId.value = userId
  membersOpen.value = false
}

function handleMapSelect(userId: string | null): void {
  if (userId) selectMember(userId)
  else selectedId.value = null
}

function handleLocating(active: boolean): void {
  locatingId.value = active ? selectedId.value : null
}

async function retryLoad(): Promise<void> {
  if (!family.family) {
    await family.load(true)
    return
  }
  const [latestOk, rosterOk] = await Promise.all([
    family.refreshLatest(),
    family.refreshRoster(),
  ])
  if (latestOk && rosterOk) ui.toast('已获取全家最新位置', 'success')
  else {
    ui.toast(
      family.latestError ?? family.rosterError ?? '连接仍然中断，请稍后重试',
      'error',
    )
  }
}

function tick(): void {
  if (document.visibilityState !== 'visible') return
  if (!family.family) return
  void family.refreshLatest()
  if (Date.now() - family.rosterSyncedAt >= ROSTER_REFRESH_INTERVAL) {
    void family.refreshRoster()
  }
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible' && family.family) {
    void family.refreshLatest()
    if (Date.now() - family.rosterSyncedAt >= VISIBLE_ROSTER_MIN_INTERVAL) {
      void family.refreshRoster()
    }
  }
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
}

function startPolling(): void {
  stopPolling()
  pollTimer = window.setInterval(tick, POLL_INTERVAL)
  document.addEventListener('visibilitychange', onVisibilityChange)
}

watch(selectedId, (id) => {
  if (!id) locatingId.value = null
})

watch(
  () => family.memberViews,
  (list) => {
    if (selectedId.value && !list.some((member) => member.userId === selectedId.value)) {
      selectedId.value = null
      locatingId.value = null
    }
  },
)

onMounted(async () => {
  ui.initNetwork()
  await family.ensureLoaded()
  startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div class="map-page">
    <aside class="member-panel" :class="{ 'is-open': membersOpen }" aria-label="家庭成员列表">
      <div class="panel-head">
        <div>
          <p class="micro">FAMILY · {{ family.family?.name ?? '我的家庭' }}</p>
          <h1 class="panel-title">家人</h1>
        </div>
        <button
          type="button"
          class="btn btn--icon btn--ghost btn--sm panel-close"
          aria-label="关闭成员列表"
          @click="membersOpen = false"
        >
          <Icon name="x" :size="18" />
        </button>
      </div>

      <p class="panel-caption">
        <span
          class="status-dot"
          :class="onlineCount > 0 ? 'status-dot--online' : 'status-dot--offline'"
        />
        <template v-if="loadingInitial">正在同步家庭位置…</template>
        <template v-else>
          {{ onlineCount }} / {{ members.length }} 人在线
          <span class="panel-caption__sep">·</span>
          {{ syncText }}
        </template>
      </p>

      <div class="member-list">
        <template v-if="loadingInitial">
          <div v-for="index in 4" :key="index" class="member-skeleton">
            <span class="skeleton member-skeleton__avatar" />
            <span class="member-skeleton__lines">
              <span class="skeleton member-skeleton__line member-skeleton__line--name" />
              <span class="skeleton member-skeleton__line member-skeleton__line--address" />
              <span class="skeleton member-skeleton__line member-skeleton__line--meta" />
            </span>
          </div>
        </template>

        <div v-else-if="fatalError" class="panel-error">
          <span class="state-orb state-orb--danger">
            <Icon name="alert" :size="22" />
          </span>
          <p class="panel-error__title">家庭地图暂时不可用</p>
          <p class="panel-error__copy">{{ family.error }}</p>
          <button type="button" class="btn btn--sm" @click="retryLoad">重新加载</button>
        </div>

        <template v-else>
          <div class="member-list__label">
            <span class="micro">全部成员</span>
            <span class="micro">{{ members.length }}</span>
          </div>
          <MemberCard
            v-for="member in members"
            :key="member.userId"
            :member="member"
            :selected="selectedId === member.userId"
            :locating="locatingId === member.userId"
            :current-user-id="currentUserId"
            :now="now"
            @select="selectMember(member.userId)"
          />

          <div v-if="members.length <= 1" class="invite-mini">
            <span class="invite-mini__art accent-teal">
              <Icon name="users" :size="18" />
            </span>
            <div>
              <p class="invite-mini__title">只差家人就完整了</p>
              <p class="invite-mini__copy">把邀请码发给家人，一起出现在地图上。</p>
            </div>
            <RouterLink to="/family" class="link-btn" aria-label="去邀请家人">
              去邀请
              <Icon name="arrow-right" :size="14" />
            </RouterLink>
          </div>
        </template>
      </div>

      <div class="panel-foot">
        <RouterLink to="/family" class="panel-foot__link">
          家庭与邀请
          <Icon name="arrow-right" :size="16" />
        </RouterLink>
        <p class="panel-foot__meta">每 10 秒自动刷新 · 页面隐藏时暂停</p>
      </div>
    </aside>

    <div class="map-region">
      <MapCanvas
        :members="members"
        :selected-id="selectedId"
        :current-user-id="currentUserId"
        :locating-user-id="locatingId"
        :drawer-open="Boolean(selectedMember)"
        @select="handleMapSelect"
      />

      <button
        type="button"
        class="members-toggle"
        aria-label="打开家庭成员列表"
        @click="membersOpen = true"
      >
        <Icon name="users" :size="16" />
        家人
        <span class="members-toggle__count">{{ members.length }}</span>
      </button>

      <div v-if="loadingInitial" class="map-syncing" role="status">
        <span class="spinner" />
        正在同步家人的位置…
      </div>

      <div v-if="connectionError" class="map-connection" role="status">
        <Icon name="wifi-off" :size="15" />
        <span>{{ connectionMessage }}</span>
        <button type="button" class="map-connection__retry" @click="retryLoad">
          {{ family.latestLoading ? '重试中…' : '重试' }}
        </button>
      </div>

      <div v-if="waitingForPositions" class="map-waiting" role="status">
        <span class="spinner" />
        正在等待家人的首次位置上报
      </div>

      <div v-if="showInviteEmpty" class="map-empty">
        <div class="map-empty__card">
          <span class="map-empty__art">
            <span class="map-empty__orb accent-teal">
              <Icon name="users" :size="26" />
            </span>
            <span class="map-empty__dot accent-coral" aria-hidden="true" />
          </span>
          <p class="micro">INVITE YOUR FAMILY</p>
          <h2 class="map-empty__title">家人还没出现在地图上</h2>
          <p class="map-empty__copy">
            把 6 位邀请码发给家人。加入之后，你们的位置会同时出现在这里。
          </p>
          <RouterLink to="/family" class="btn btn--primary">
            邀请家人
            <Icon name="arrow-right" :size="16" />
          </RouterLink>
        </div>
      </div>

      <div v-if="fatalError" class="map-state">
        <div class="map-state__card">
          <StateBlock
            tone="danger"
            icon="alert"
            title="家庭地图暂时不可用"
            :description="family.error ?? '请检查网络连接后重试。'"
            action-label="重新加载"
            :busy="family.loading"
            @action="retryLoad"
          />
        </div>
      </div>

      <Transition name="drawer">
        <div v-if="selectedMember" class="detail-slot">
          <MemberDrawer
            :member="selectedMember"
            @close="selectedId = null"
            @locating="handleLocating"
          />
        </div>
      </Transition>
    </div>

    <div
      v-if="membersOpen"
      class="panel-scrim"
      aria-hidden="true"
      @click="membersOpen = false"
    />
  </div>
</template>

<style scoped>
.map-page {
  position: relative;
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  height: calc(100dvh - var(--topbar-h));
  min-height: 0;
  overflow: hidden;
  background: var(--white);
}

/* ---- Member panel ------------------------------------------------------- */

.member-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 28px 14px 16px;
  background: var(--white);
  border-right: 1px solid var(--ring);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 0 10px;
}

.panel-title {
  margin-top: 6px;
  font-size: 32px;
  line-height: 1.12;
  letter-spacing: -0.03em;
  font-weight: 500;
}

.panel-close {
  display: none;
}

.panel-caption {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding: 0 10px;
  color: var(--ink-muted);
  font-size: 11.5px;
  line-height: 1.4;
}

.panel-caption__sep {
  opacity: 0.5;
}

.member-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 22px;
  padding: 0 0 12px;
}

.member-list__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 8px;
}

.member-list__label .micro {
  font-size: 9.5px;
}

.member-skeleton {
  display: flex;
  gap: 12px;
  padding: 12px;
}

.member-skeleton__avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.member-skeleton__lines {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
  padding-top: 3px;
}

.member-skeleton__line {
  height: 10px;
  border-radius: 4px;
}

.member-skeleton__line--name {
  width: 42%;
  height: 12px;
}

.member-skeleton__line--address {
  width: 82%;
}

.member-skeleton__line--meta {
  width: 34%;
  height: 8px;
}

.panel-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 20px 10px;
}

.panel-error .state-orb {
  width: 42px;
  height: 42px;
}

.panel-error__title {
  margin-top: 4px;
  font-size: 15px;
  font-weight: 600;
}

.panel-error__copy {
  margin-bottom: 4px;
  color: var(--slate);
  font-size: 12.5px;
  line-height: 1.55;
}

.invite-mini {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  margin: 14px 4px 0;
  padding: 14px;
  border-radius: var(--r-card);
  box-shadow: inset 0 0 0 1px var(--ring);
}

.invite-mini__art {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
}

.invite-mini__title {
  font-size: 13px;
  font-weight: 600;
}

.invite-mini__copy {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 11px;
  line-height: 1.45;
}

.invite-mini .link-btn {
  grid-column: 2;
  justify-self: start;
  font-size: 12px;
}

.panel-foot {
  flex: 0 0 auto;
  padding: 12px 6px 0;
  border-top: 1px solid var(--line);
}

.panel-foot__link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 10px;
  border-radius: 8px;
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
  transition: background-color 0.14s;
}

.panel-foot__link:hover {
  background: var(--surface-soft);
}

.panel-foot__meta {
  margin-top: 4px;
  padding: 0 10px;
  color: var(--ink-muted);
  font-size: 10.5px;
  line-height: 1.5;
}

/* ---- Map region --------------------------------------------------------- */

.map-region {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.members-toggle {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 36;
  display: none;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--ring);
  box-shadow: 0 6px 20px -16px rgba(28, 28, 30, 0.35);
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
  backdrop-filter: blur(6px);
}

.members-toggle__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--blue-soft);
  color: var(--blue-pressed);
  font-size: 10px;
  font-weight: 600;
}

.map-syncing {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 32;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--ring);
  color: var(--slate);
  font-size: 12px;
  white-space: nowrap;
  pointer-events: none;
}

.map-waiting {
  position: absolute;
  top: 62px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 32;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 13px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--ring);
  color: var(--ink-muted);
  font-size: 11.5px;
  white-space: nowrap;
  pointer-events: none;
}

.map-connection {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 40;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: calc(100% - 32px);
  padding: 8px 12px;
  border-radius: var(--r-pill);
  background: var(--orange);
  color: var(--orange-ink);
  font-size: 12px;
  line-height: 1.4;
  box-shadow: 0 0 0 1px rgba(122, 67, 17, 0.08);
}

.map-connection__retry {
  padding: 2px 6px;
  border-radius: 5px;
  color: var(--orange-ink);
  font-size: 11.5px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.map-connection__retry:hover {
  background: rgba(122, 67, 17, 0.08);
}

/* ---- Empty / error overlays -------------------------------------------- */

.map-empty,
.map-state {
  position: absolute;
  inset: 0;
  z-index: 42;
  display: grid;
  place-items: center;
  padding: 24px;
  pointer-events: none;
}

.map-empty__card {
  width: min(390px, 100%);
  padding: 30px;
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
  pointer-events: auto;
  animation: rise-in 0.3s var(--ease-out);
}

.map-empty__art {
  position: relative;
  display: inline-flex;
  margin-bottom: 20px;
}

.map-empty__orb {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 18px;
}

.map-empty__dot {
  position: absolute;
  right: -14px;
  bottom: -8px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 3px solid #fff;
}

.map-empty__title {
  margin-top: 8px;
  font-size: 24px;
  line-height: 1.25;
  letter-spacing: -0.025em;
  font-weight: 600;
}

.map-empty__copy {
  margin: 10px 0 20px;
  color: var(--slate);
  font-size: 14px;
  line-height: 1.65;
}

.map-state {
  z-index: 55;
  pointer-events: auto;
  background: rgba(245, 246, 248, 0.68);
  backdrop-filter: blur(3px);
}

.map-state__card {
  width: min(430px, 100%);
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
}

@keyframes rise-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ---- Detail drawer ------------------------------------------------------ */

.detail-slot {
  position: absolute;
  top: 16px;
  right: 16px;
  bottom: 16px;
  z-index: 45;
  width: 360px;
  display: flex;
  min-height: 0;
}

.drawer-enter-active,
.drawer-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.26s var(--ease-out);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

/* ---- Mobile drawer for the member panel --------------------------------- */

.panel-scrim {
  display: none;
}

@media (max-width: 1024px) {
  .map-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .member-panel {
    position: fixed;
    top: var(--topbar-h);
    left: 0;
    bottom: 0;
    z-index: 710;
    width: min(340px, 88vw);
    padding-top: 24px;
    box-shadow: 24px 0 70px -40px rgba(28, 28, 30, 0.5);
    transform: translateX(-105%);
    transition: transform 0.26s var(--ease-out);
  }

  .member-panel.is-open {
    transform: translateX(0);
  }

  .panel-close {
    display: inline-flex;
  }

  .members-toggle {
    display: inline-flex;
  }

  .panel-scrim {
    display: block;
    position: fixed;
    inset: var(--topbar-h) 0 0 0;
    z-index: 700;
    background: rgba(28, 28, 30, 0.2);
    backdrop-filter: blur(1px);
  }

  .map-connection {
    top: 58px;
  }

  .map-waiting {
    top: 58px;
  }
}

@media (max-width: 900px) {
  .detail-slot {
    top: auto;
    left: 0;
    right: 0;
    bottom: 0;
    width: auto;
    z-index: 60;
    display: block;
  }

  .drawer-enter-from,
  .drawer-leave-to {
    transform: translateY(26px);
  }

  .map-empty__card {
    padding: 26px 22px;
  }

  .map-empty__title {
    font-size: 22px;
  }

  .map-empty {
    align-items: end;
    padding: 16px 16px 90px;
  }
}

@media (max-width: 600px) {
  .members-toggle {
    top: 12px;
    left: 12px;
  }

  .map-connection {
    top: 56px;
    left: 12px;
    right: 12px;
    transform: none;
    max-width: none;
    justify-content: center;
  }

  .map-waiting {
    top: auto;
    bottom: 68px;
    max-width: calc(100% - 24px);
    white-space: normal;
    text-align: center;
  }
}
</style>
