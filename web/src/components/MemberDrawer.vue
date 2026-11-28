<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api'
import { ApiError } from '@/api/errors'
import type { MemberView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useFollowsStore } from '@/stores/follows'
import { useUiStore } from '@/stores/ui'
import { messageForError } from '@/lib/errors'
import { isStaleLocation } from '@/lib/geo'
import {
  formatAccuracy,
  formatBattery,
  formatClock,
  formatSpeed,
  relativeTime,
} from '@/lib/format'
import { usePlaceLabel } from '@/composables/usePlaceLabel'
import Avatar from './Avatar.vue'
import FollowSheet from './FollowSheet.vue'
import Icon from './Icon.vue'

const props = defineProps<{ member: MemberView }>()
const emit = defineEmits<{ close: []; locating: [active: boolean] }>()

const router = useRouter()
const auth = useAuthStore()
const family = useFamilyStore()
const follows = useFollowsStore()
const ui = useUiStore()

const followOpen = ref(false)
const isMe = computed(() => props.member.userId === auth.user?.id)
const isFollowed = computed(() => Boolean(follows.byTarget(props.member.userId)))

onMounted(() => {
  void follows.ensureLoaded()
})

type LocateState = 'idle' | 'pending' | 'success' | 'error'

const locateState = ref<LocateState>('idle')
const locateMessage = ref('')
let jobId = 0
let pollTimer: number | null = null

const device = computed(
  () => family.devices.find((item) => item.id === props.member.deviceId) ?? null,
)
const location = computed(() => props.member.lastLocation)
const { label: address } = usePlaceLabel(location, { empty: '暂无位置数据' })
const updatedLabel = computed(() =>
  location.value ? relativeTime(location.value.recordedAt) : '暂无更新',
)
const stale = computed(() => isStaleLocation(location.value))
const paused = computed(() => props.member.sharingPaused)
const offline = computed(() => !props.member.online)
const canLocate = computed(() => Boolean(props.member.deviceId) && !paused.value)

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => {
    pollTimer = window.setTimeout(resolve, ms)
  })
}

function cancelLocate(): void {
  jobId += 1
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

watch(
  () => props.member.userId,
  () => {
    cancelLocate()
    locateState.value = 'idle'
    locateMessage.value = ''
  },
)

watch(locateState, (value) => {
  emit('locating', value === 'pending')
})

async function startLocate(): Promise<void> {
  if (!props.member.deviceId) {
    locateState.value = 'error'
    locateMessage.value = '该成员还没有绑定设备，暂时无法立即定位。'
    return
  }
  if (paused.value) {
    locateState.value = 'error'
    locateMessage.value = '对方已暂停位置共享，无法请求新的位置。'
    return
  }

  const currentJob = ++jobId
  locateState.value = 'pending'
  locateMessage.value = ''
  const startedAt = Date.now()

  try {
    const command = await api.locate.request(props.member.deviceId)
    if (currentJob !== jobId) return
    const deadline = new Date(command.deadlineAt).getTime() + 3000

    while (Date.now() < deadline) {
      await wait(1500)
      if (currentJob !== jobId) return
      const status = await api.locate.command(command.commandId)
      if (currentJob !== jobId) return
      if (status.status === 'completed') {
        await family.refreshLatest()
        if (currentJob !== jobId) return
        locateState.value = 'success'
        locateMessage.value = '已获取最新位置'
        ui.toast(`已获取${props.member.nickname}的最新位置`, 'success')
        return
      }
      if (status.status === 'timeout') {
        throw new ApiError(408, 'LOCATE_TIMEOUT', '定位请求超时')
      }
      if (status.status === 'failed') {
        throw new ApiError(500, 'LOCATE_FAILED', '对方定位失败')
      }
    }
    throw new ApiError(408, 'LOCATE_TIMEOUT', '定位请求超时')
  } catch (err) {
    if (currentJob !== jobId) return
    locateState.value = 'error'
    locateMessage.value = friendlyLocateError(err, Date.now() - startedAt)
  }
}

function friendlyLocateError(err: unknown, elapsed: number): string {
  if (err instanceof ApiError) {
    if (err.code === 'DEVICE_OFFLINE') return '对方当前离线，无法立即定位。'
    if (err.code === 'FORBIDDEN' || err.code === 'NOT_FOUND') {
      return '你无权查看该成员的位置，或该设备已被解绑。'
    }
    if (err.code === 'LOCATE_TIMEOUT' || err.code === 'LOCATE_FAILED') {
      return '请求超时，对方可能未开启定位。请稍后再试。'
    }
    return messageForError(err)
  }
  if (elapsed > 32_000) return '请求超时，对方可能未开启定位。'
  return messageForError(err, '立即定位失败，请稍后再试')
}

function openHistory(): void {
  if (!props.member.deviceId) return
  void router.push(`/history/${props.member.deviceId}`)
}

function openNavigation(): void {
  if (!location.value) return
  const url = `https://uri.amap.com/marker?position=${location.value.lng},${location.value.lat}&name=${encodeURIComponent(
    props.member.nickname,
  )}&callnative=0`
  window.open(url, '_blank', 'noopener,noreferrer')
}

onBeforeUnmount(() => {
  cancelLocate()
  emit('locating', false)
})
</script>

<template>
  <aside class="member-detail" aria-label="成员详情">
    <div class="member-detail__head">
      <p class="member-detail__eyebrow">成员详情</p>
      <button
        type="button"
        class="member-detail__close"
        aria-label="关闭成员详情"
        @click="emit('close')"
      >
        <Icon name="x" :size="14" />
      </button>
    </div>

    <div class="member-detail__scroll">
      <section class="member-detail__identity">
        <div class="member-detail__avatar">
          <Avatar :name="member.nickname" :id="member.userId" :size="64" :muted="offline" />
          <span
            class="status-dot member-detail__status"
            :class="
              paused
                ? 'status-dot--paused'
                : offline
                  ? 'status-dot--offline'
                  : 'status-dot--online'
            "
          />
        </div>
        <div class="member-detail__name-block">
          <h2 class="member-detail__name">{{ member.nickname }}</h2>
        </div>
      </section>

      <section class="member-detail__address">
        <p class="member-detail__label">当前位置</p>
        <h3 class="member-detail__address-text">{{ address }}</h3>
        <p class="member-detail__address-meta">
          <span v-if="stale" class="chip chip--warn">位置已过期</span>
          <span v-else-if="paused" class="chip chip--warn">已暂停共享</span>
        </p>
      </section>

      <div v-if="paused" class="alert alert--warn member-detail__note">
        <Icon name="shield" :size="16" />
        <span>对方已暂停位置共享，只能看到最后一次位置。</span>
      </div>
      <div v-else-if="!member.deviceId" class="alert alert--info member-detail__note">
        <Icon name="info" :size="16" />
        <span>这位家人还没有绑定设备。</span>
      </div>

      <dl class="member-detail__metrics">
        <div class="metric">
          <dt>更新时间</dt>
          <dd>{{ updatedLabel }}</dd>
        </div>
        <div class="metric">
          <dt>定位精度</dt>
          <dd>{{ formatAccuracy(location?.accuracy) }}</dd>
        </div>
        <div class="metric">
          <dt>电量</dt>
          <dd>{{ formatBattery(location?.battery) }}</dd>
          <span v-if="location?.battery != null" class="metric__bar">
            <span
              class="metric__bar-fill"
              :style="{ width: `${Math.min(100, Math.max(0, location.battery))}%` }"
            />
          </span>
        </div>
        <div class="metric">
          <dt>速度</dt>
          <dd>{{ formatSpeed(location?.speed) }}</dd>
        </div>
      </dl>

      <div
        v-if="locateState === 'pending'"
        class="locate-panel locate-panel--pending"
        role="status"
        aria-live="polite"
      >
        <span class="spinner" />
        <div>
          <strong>正在请求对方定位…</strong>
          <p>通常几秒钟内完成，也可以先查看最后位置。</p>
        </div>
      </div>
      <div
        v-else-if="locateState === 'success'"
        class="locate-panel locate-panel--success"
        role="status"
      >
        <Icon name="check" :size="17" />
        <div>
          <strong>{{ locateMessage }}</strong>
          <p>位置与时间已经更新。</p>
        </div>
      </div>
      <div v-else-if="locateState === 'error'" class="locate-panel locate-panel--error" role="alert">
        <Icon name="alert" :size="17" />
        <div>
          <strong>立即定位失败</strong>
          <p>{{ locateMessage }}</p>
        </div>
      </div>

      <div class="member-detail__actions">
        <button
          type="button"
          class="btn btn--primary btn--block"
          :disabled="!canLocate || locateState === 'pending'"
          @click="startLocate"
        >
          <span v-if="locateState === 'pending'" class="spinner" />
          <Icon v-else name="pin" :size="17" />
          {{ locateState === 'pending' ? '正在定位…' : '立即定位' }}
        </button>
        <button
          type="button"
          class="btn member-detail__secondary"
          :disabled="!member.deviceId"
          :title="member.deviceId ? '查看历史轨迹' : '该成员还没有绑定设备'"
          @click="openHistory"
        >
          <Icon name="clock" :size="16" />
          查看历史轨迹
        </button>
        <button
          v-if="!isMe"
          type="button"
          class="btn member-detail__secondary"
          :class="{ 'is-followed': isFollowed }"
          @click="followOpen = true"
        >
          <Icon name="bell" :size="16" />
          {{ isFollowed ? '已关注 · 提醒设置' : '关注 TA 的位置变化' }}
        </button>
        <button
          type="button"
          class="member-detail__link"
          :disabled="!location"
          @click="openNavigation"
        >
          <Icon name="navigation" :size="14" />
          在高德地图中导航
        </button>
      </div>

      <div class="member-detail__device">
        <p class="member-detail__label">设备与上报</p>
        <div class="device-row">
          <span class="device-row__icon">
            <Icon name="device" :size="16" />
          </span>
          <span class="device-row__copy">
            <span class="device-row__name">{{ device?.name ?? '未绑定设备' }}</span>
            <span class="device-row__meta">
              {{ device ? device.osVersion : '—' }} · App v0.1
            </span>
          </span>
          <span class="device-row__time">{{ formatClock(location?.recordedAt) }}</span>
        </div>
      </div>

      <FollowSheet
        :open="followOpen"
        :member="{ userId: member.userId, nickname: member.nickname }"
        @close="followOpen = false"
      />
    </div>
  </aside>
</template>

<style scoped>
.member-detail {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--white);
  border-radius: var(--r-panel);
  box-shadow: var(--panel-shadow-lifted);
  overflow: hidden;
}

.member-detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 24px 24px 0;
}

.member-detail__eyebrow {
  color: var(--ink-muted);
  font-size: 10.5px;
  line-height: 1.3;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  font-weight: 600;
}

.member-detail__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--surface-soft);
  color: var(--slate);
  transition: background-color 0.16s, color 0.16s;
}

.member-detail__close:hover {
  background: var(--surface-sunken);
  color: var(--ink);
}

.member-detail__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px 24px;
}

.member-detail__identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.member-detail__avatar {
  position: relative;
  flex: 0 0 auto;
  display: flex;
}

.member-detail__status {
  position: absolute;
  right: 1px;
  bottom: 2px;
  width: 11px;
  height: 11px;
  border: 2px solid #fff;
  box-shadow: none;
}

.member-detail__name-block {
  min-width: 0;
}

.member-detail__name {
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: -0.025em;
  font-weight: 500;
}

.member-detail__online {
  margin-top: 3px;
  color: var(--slate);
  font-size: 11.5px;
  line-height: 1.4;
}

.member-detail__label {
  color: var(--ink-muted);
  font-size: 10.5px;
  line-height: 1.3;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  font-weight: 600;
}

.member-detail__address {
  margin-top: 20px;
}

.member-detail__address-text {
  margin-top: 6px;
  font-size: 22px;
  line-height: 1.35;
  letter-spacing: -0.02em;
  font-weight: 500;
  word-break: break-word;
}

.member-detail__address-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.member-detail__note {
  margin-top: 14px;
}

.member-detail__metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 18px 0 0;
}

.metric {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  min-height: 70px;
  padding: 11px 12px;
  border-radius: 12px;
  box-shadow: inset 0 0 0 1px var(--ring);
  background: var(--white);
}

.metric dt {
  font-size: 10.5px;
  line-height: 1.3;
  letter-spacing: 0.06em;
  color: var(--ink-muted);
  font-weight: 500;
}

.metric dd {
  margin: 0;
  font-size: 16px;
  line-height: 1.35;
  color: var(--ink);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.metric__bar {
  display: block;
  height: 3px;
  margin-top: auto;
  border-radius: 2px;
  background: var(--input-line);
  overflow: hidden;
}

.metric__bar-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--success);
}

.locate-panel {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  margin-top: 12px;
  font-size: 12.5px;
  line-height: 1.5;
}

.locate-panel strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
}

.locate-panel p {
  margin-top: 2px;
  opacity: 0.8;
}

.locate-panel--pending {
  background: var(--orange);
  color: var(--orange-ink);
}

.locate-panel--success {
  background: var(--teal);
  color: var(--teal-ink);
}

.locate-panel--error {
  background: var(--red);
  color: var(--red-ink);
}

.member-detail__actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 18px;
}

.member-detail__actions .btn--primary {
  min-height: 48px;
  font-size: 15px;
  border-radius: 8px;
}

.member-detail__secondary {
  min-height: 44px;
  font-size: 14px;
  font-weight: 600;
  border-radius: 8px;
}

.member-detail__secondary.is-followed {
  background: var(--blue-soft);
  color: var(--blue-pressed);
  box-shadow: inset 0 0 0 1px rgba(91, 118, 254, 0.18);
}

.member-detail__link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 24px;
  color: var(--blue);
  font-size: 12px;
  font-weight: 600;
}

.member-detail__link:disabled {
  color: var(--ink-muted);
  opacity: 0.6;
}

.member-detail__device {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--input-line);
}

.device-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.device-row__icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--surface-soft);
  color: var(--slate);
  flex: 0 0 auto;
}

.device-row__copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.device-row__name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.device-row__meta {
  font-size: 10.5px;
  color: var(--ink-muted);
}

.device-row__time {
  font-size: 10.5px;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .member-detail {
    height: auto;
    max-height: 78dvh;
    border-radius: var(--r-panel-lg) var(--r-panel-lg) 0 0;
    box-shadow:
      0 -1px 0 var(--ring),
      0 -30px 70px -30px rgba(28, 28, 30, 0.4);
  }

  .member-detail__head {
    padding: 14px 20px 0;
  }

  .member-detail__head::before {
    content: '';
    position: absolute;
    top: 7px;
    left: 50%;
    transform: translateX(-50%);
    width: 36px;
    height: 4px;
    border-radius: 999px;
    background: #e7e8ed;
  }

  .member-detail__head {
    position: relative;
  }

  .member-detail__scroll {
    padding: 16px 20px 22px;
  }

  .member-detail__address-text {
    font-size: 20px;
  }
}
</style>
