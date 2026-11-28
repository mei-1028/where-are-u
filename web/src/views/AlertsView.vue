<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { AlertInfo, AlertRuleType } from '@/api/types'
import { useAlertsStore } from '@/stores/alerts'
import { useFollowsStore } from '@/stores/follows'
import { useUiStore } from '@/stores/ui'
import { relativeTime } from '@/lib/format'
import {
  notificationPermission,
  notificationsSupported,
  requestNotificationPermission,
} from '@/lib/notify'
import Icon from '@/components/Icon.vue'

const router = useRouter()
const alerts = useAlertsStore()
const follows = useFollowsStore()
const ui = useUiStore()

const permission = ref(notificationPermission())
const enabling = ref(false)

const supported = notificationsSupported()
const showEnable = computed(
  () => supported && permission.value === 'default',
)

const TYPE_META: Record<
  AlertRuleType,
  { icon: string; tone: string; label: string }
> = {
  MOVEMENT: { icon: 'navigation', tone: 'blue', label: '位置变动' },
  STALE: { icon: 'clock', tone: 'orange', label: '长时间未更新' },
  LOW_BATTERY: { icon: 'battery', tone: 'coral', label: '低电量' },
  GEOFENCE: { icon: 'pin', tone: 'teal', label: '到达/离开' },
}

function metaOf(type: AlertRuleType) {
  return TYPE_META[type] ?? { icon: 'bell', tone: 'blue', label: '提醒' }
}

async function enableNotifications(): Promise<void> {
  if (enabling.value) return
  enabling.value = true
  try {
    const granted = await requestNotificationPermission()
    permission.value = notificationPermission()
    ui.toast(
      granted ? '已开启桌面通知' : '未获得通知权限，可在浏览器设置中开启',
      granted ? 'success' : 'error',
    )
  } finally {
    enabling.value = false
  }
}

function openAlert(alert: AlertInfo): void {
  void alerts.markRead(alert.id)
  void router.push({ name: 'map', query: { member: alert.target.userId } })
}

function openMember(userId: string): void {
  void router.push({ name: 'map', query: { member: userId } })
}

onMounted(() => {
  void alerts.load(true)
  void follows.loadFollowers(true)
})
</script>

<template>
  <div class="page-shell">
    <div class="page-card alerts-page">
      <header class="page-head">
        <div class="page-head__meta">
          <h1 class="page-title">消息</h1>
          <p class="page-sub">
            关注的人位置变化、失联或电量过低时，会在这里留下记录。
          </p>
        </div>
        <div class="page-head__actions">
          <button
            v-if="showEnable"
            type="button"
            class="btn btn--sm"
            :disabled="enabling"
            @click="enableNotifications"
          >
            <Icon name="bell" :size="15" />
            开启桌面通知
          </button>
          <span v-else-if="supported && permission === 'granted'" class="chip chip--success">
            桌面通知已开启
          </span>
          <button
            type="button"
            class="btn btn--sm"
            :disabled="!alerts.hasUnread"
            @click="alerts.markAllRead()"
          >
            全部已读
          </button>
        </div>
      </header>

      <section class="card alert-card">
        <div class="alert-card__head">
          <span class="card-label">全部提醒</span>
          <span v-if="alerts.unreadCount > 0" class="chip chip--blue">
            {{ alerts.unreadCount }} 条未读
          </span>
        </div>

        <div v-if="alerts.loading && alerts.items.length === 0" class="state-line">
          正在加载消息…
        </div>
        <div v-else-if="alerts.error && alerts.items.length === 0" class="state-line">
          {{ alerts.error }}
        </div>
        <div v-else-if="alerts.items.length === 0" class="empty-state">
          <span class="empty-state__icon">
            <Icon name="bell" :size="22" />
          </span>
          <p class="empty-state__title">还没有提醒</p>
          <p class="empty-state__desc">
            在地图上打开一位家人，点「关注」即可设置位置变动、失联和低电量提醒。
          </p>
        </div>

        <ul v-else class="alert-list">
          <li v-for="alert in alerts.items" :key="alert.id">
            <button
              type="button"
              class="alert-row"
              :class="{ 'is-unread': !alert.readAt }"
              @click="openAlert(alert)"
            >
              <span class="alert-row__icon" :class="`alert-row__icon--${metaOf(alert.type).tone}`">
                <Icon :name="metaOf(alert.type).icon" :size="18" />
              </span>
              <span class="alert-row__copy">
                <span class="alert-row__top">
                  <strong class="alert-row__title">{{ alert.title }}</strong>
                  <span class="alert-row__time">{{ relativeTime(alert.createdAt) }}</span>
                </span>
                <span class="alert-row__body">{{ alert.body }}</span>
                <span class="alert-row__meta">
                  {{ metaOf(alert.type).label }}
                  <template v-if="alert.silent"> · 夜间免打扰</template>
                </span>
              </span>
              <span v-if="!alert.readAt" class="alert-row__dot" aria-label="未读" />
            </button>
          </li>
        </ul>
      </section>

      <section v-if="follows.followers.length > 0" class="card followers-card">
        <div class="card-head">
          <span class="card-label">谁在关注我</span>
        </div>
        <div class="followers">
          <button
            v-for="follower in follows.followers"
            :key="follower.userId"
            type="button"
            class="follower-chip"
            @click="openMember(follower.userId)"
          >
            <Icon name="bell" :size="13" />
            {{ follower.nickname }}
            <span class="follower-chip__time">{{ relativeTime(follower.since) }}</span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.alerts-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.alert-card {
  padding: 18px 20px 8px;
}

.alert-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
}

.state-line {
  padding: 28px 4px;
  color: var(--ink-muted);
  font-size: 13px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 40px 20px 46px;
  text-align: center;
}

.empty-state__icon {
  width: 46px;
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: var(--surface-soft);
  color: var(--ink-muted);
  margin-bottom: 4px;
}

.empty-state__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}

.empty-state__desc {
  max-width: 340px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-muted);
}

.alert-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.alert-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  padding: 14px 8px;
  border-radius: 10px;
  text-align: left;
  transition: background-color 0.14s;
}

.alert-row:hover {
  background: var(--surface-soft);
}

.alert-row + .alert-row {
  border-top: 1px solid var(--line);
}

.alert-row__icon {
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
}

.alert-row__icon--blue {
  background: var(--blue-soft);
  color: var(--blue);
}

.alert-row__icon--orange {
  background: var(--orange);
  color: var(--orange-ink);
}

.alert-row__icon--coral {
  background: var(--coral);
  color: var(--coral-ink);
}

.alert-row__icon--teal {
  background: var(--teal);
  color: var(--teal-ink);
}

.alert-row__copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.alert-row__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.alert-row__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.alert-row.is-unread .alert-row__title {
  color: var(--blue-pressed);
}

.alert-row__time {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--ink-muted);
}

.alert-row__body {
  font-size: 12.5px;
  color: var(--slate);
  line-height: 1.5;
}

.alert-row__meta {
  font-size: 11px;
  color: var(--ink-muted);
}

.alert-row__dot {
  position: absolute;
  right: 10px;
  top: 18px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--blue);
}

.followers-card {
  padding: 16px 20px 18px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
}

.followers {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.follower-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: var(--r-pill);
  background: var(--surface-soft);
  color: var(--slate);
  font-size: 12.5px;
  transition: background-color 0.14s;
}

.follower-chip:hover {
  background: var(--surface-sunken);
  color: var(--ink);
}

.follower-chip__time {
  font-size: 11px;
  color: var(--ink-muted);
}
</style>
