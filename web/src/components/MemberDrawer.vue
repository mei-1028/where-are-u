<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api'
import { ApiError } from '@/api/errors'
import type { MemberView } from '@/api/types'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { copyText } from '@/lib/clipboard'
import { messageForError } from '@/lib/errors'
import { describeLocation, isStaleLocation, locationAgeMs } from '@/lib/geo'
import {
  formatAccuracy,
  formatBattery,
  formatClock,
  formatCoordinate,
  formatDateTime,
  formatSpeed,
  relativeTime,
} from '@/lib/format'
import Avatar from './Avatar.vue'
import Icon from './Icon.vue'

const props = defineProps<{ member: MemberView }>()
const emit = defineEmits<{ close: []; locating: [active: boolean] }>()

const router = useRouter()
const family = useFamilyStore()
const ui = useUiStore()

type LocateState = 'idle' | 'pending' | 'success' | 'error'

const locateState = ref<LocateState>('idle')
const locateMessage = ref('')
let jobId = 0
let pollTimer: number | null = null

const device = computed(
  () => family.devices.find((item) => item.id === props.member.deviceId) ?? null,
)
const location = computed(() => props.member.lastLocation)
const address = computed(() =>
  location.value
    ? describeLocation(location.value.lat, location.value.lng)
    : '暂无位置数据',
)
const updatedLabel = computed(() =>
  location.value ? relativeTime(location.value.recordedAt) : '暂无更新',
)
const exactUpdated = computed(() =>
  location.value ? `${formatDateTime(location.value.recordedAt)} · ${formatClock(location.value.recordedAt)}` : '—',
)
const stale = computed(() => isStaleLocation(location.value))
const paused = computed(() => props.member.sharingPaused)
const offline = computed(() => !props.member.online)
const canLocate = computed(() => Boolean(props.member.deviceId) && !paused.value)
const ageLabel = computed(() => {
  const age = locationAgeMs(location.value)
  if (age === null) return '暂无位置'
  return offline.value ? `最后在线 ${updatedLabel.value}` : `更新于 ${updatedLabel.value}`
})
const sharingLabel = computed(() => (paused.value ? '已暂停共享' : '共享中'))
const roleLabel = computed(() => (props.member.role === 'admin' ? '管理员' : '成员'))

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

async function copyCoordinates(): Promise<void> {
  if (!location.value) return
  const text = formatCoordinate(location.value.lat, location.value.lng)
  const ok = await copyText(text)
  if (ok) ui.toast('坐标已复制', 'success')
  else ui.toast('复制失败，请手动选择坐标', 'error')
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
      <div class="member-detail__identity">
        <div class="member-detail__avatar">
          <Avatar :name="member.nickname" :id="member.userId" :size="54" :muted="offline" />
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
          <p class="micro">MEMBER · {{ roleLabel }}</p>
          <h2 class="member-detail__name">{{ member.nickname }}</h2>
          <p class="member-detail__online">
            {{ paused ? '已暂停共享' : offline ? '离线' : '在线' }}
            <span class="dot-sep">·</span>
            {{ ageLabel }}
          </p>
        </div>
      </div>
      <button
        type="button"
        class="btn btn--icon btn--ghost btn--sm"
        aria-label="关闭成员详情"
        @click="emit('close')"
      >
        <Icon name="x" :size="18" />
      </button>
    </div>

    <div class="member-detail__scroll">
      <section class="member-detail__address">
        <p class="micro">当前地址</p>
        <h3 class="member-detail__address-text">{{ address }}</h3>
        <p class="member-detail__address-meta">
          <Icon name="clock" :size="14" />
          <span :title="exactUpdated">{{ ageLabel }}</span>
          <span v-if="stale" class="chip chip--warn">位置已过期</span>
          <span v-else-if="paused" class="chip chip--warn">已暂停共享</span>
        </p>
      </section>

      <div v-if="paused" class="alert alert--warn member-detail__note">
        <Icon name="shield" :size="16" />
        <span>对方已暂停位置共享。家人端会保留最后一次位置，但无法请求新位置。</span>
      </div>
      <div v-else-if="!member.deviceId" class="alert alert--info member-detail__note">
        <Icon name="info" :size="16" />
        <span>这位家人还没有绑定设备，因此暂时没有位置与电量数据。</span>
      </div>

      <dl class="member-detail__metrics">
        <div class="metric">
          <dt>最后更新</dt>
          <dd>{{ updatedLabel }}</dd>
        </div>
        <div class="metric">
          <dt>定位精度</dt>
          <dd>{{ formatAccuracy(location?.accuracy) }}</dd>
        </div>
        <div class="metric">
          <dt>设备电量</dt>
          <dd>{{ formatBattery(location?.battery) }}</dd>
        </div>
        <div class="metric">
          <dt>移动速度</dt>
          <dd>{{ formatSpeed(location?.speed) }}</dd>
        </div>
        <div class="metric metric--wide">
          <dt>设备</dt>
          <dd>{{ device?.name ?? '未绑定设备' }}</dd>
        </div>
        <div class="metric metric--wide">
          <dt>坐标</dt>
          <dd class="metric__coord">
            <button
              v-if="location"
              type="button"
              class="coord-button"
              title="复制坐标"
              @click="copyCoordinates"
            >
              {{ formatCoordinate(location.lat, location.lng) }}
              <Icon name="copy" :size="13" />
            </button>
            <span v-else>—</span>
          </dd>
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
        <div class="member-detail__secondary">
          <button
            type="button"
            class="btn"
            :disabled="!member.deviceId"
            :title="member.deviceId ? '查看历史轨迹' : '该成员还没有绑定设备'"
            @click="openHistory"
          >
            <Icon name="clock" :size="16" />
            历史轨迹
          </button>
          <button
            type="button"
            class="btn"
            :disabled="!location"
            @click="openNavigation"
          >
            <Icon name="navigation" :size="16" />
            导航
          </button>
        </div>
        <p class="member-detail__sharing">
          <span class="status-dot" :class="paused ? 'status-dot--paused' : 'status-dot--online'" />
          {{ sharingLabel }} · 最后在线数据 {{ relativeTime(member.lastSeenAt) }}
        </p>
        <p class="member-detail__privacy">
          <Icon name="shield" :size="12" />
          位置仅在家庭成员之间共享
        </p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.member-detail {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--white);
  border-radius: var(--r-panel);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
  overflow: hidden;
}

.member-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 22px 22px 18px;
}

.member-detail__identity {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.member-detail__avatar {
  position: relative;
  flex: 0 0 auto;
}

.member-detail__status {
  position: absolute;
  right: 1px;
  bottom: 2px;
  width: 12px;
  height: 12px;
  border: 2.5px solid #fff;
}

.member-detail__name-block {
  min-width: 0;
}

.member-detail__name {
  margin-top: 4px;
  font-size: 26px;
  line-height: 1.2;
  letter-spacing: -0.025em;
  font-weight: 600;
}

.member-detail__online {
  margin-top: 4px;
  color: var(--ink-muted);
  font-size: 12.5px;
  line-height: 1.4;
}

.dot-sep {
  margin: 0 4px;
}

.member-detail__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 22px 24px;
}

.member-detail__address {
  padding: 18px 0 20px;
  border-top: 1px solid var(--line);
}

.member-detail__address .micro {
  margin-bottom: 8px;
}

.member-detail__address-text {
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
  margin-top: 10px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.5;
}

.member-detail__note {
  margin-bottom: 16px;
}

.member-detail__metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  margin: 0 0 20px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.metric {
  padding: 14px 0;
  min-width: 0;
}

.metric + .metric {
  border-left: 1px solid var(--line);
  padding-left: 16px;
}

.metric:nth-child(3) {
  border-left: 0;
  padding-left: 0;
}

.metric:nth-child(n + 3) {
  border-top: 1px solid var(--line);
}

.metric--wide {
  grid-column: 1 / -1;
  border-left: 0;
  padding-left: 0;
  border-top: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.metric dt {
  font-size: 10px;
  line-height: 1.3;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-muted);
  font-weight: 600;
}

.metric dd {
  margin: 5px 0 0;
  font-size: 14.5px;
  color: var(--ink);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.metric--wide dd {
  margin: 0;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric__coord {
  min-width: 0;
}

.coord-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--slate);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  border-radius: 5px;
  padding: 2px 4px;
  margin-right: -4px;
  transition:
    background-color 0.14s,
    color 0.14s;
}

.coord-button:hover {
  background: var(--surface-soft);
  color: var(--ink);
}

.locate-panel {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  margin-bottom: 16px;
  font-size: 13px;
  line-height: 1.5;
}

.locate-panel strong {
  display: block;
  font-size: 13.5px;
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
  gap: 10px;
}

.member-detail__secondary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.member-detail__sharing {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 4px;
  color: var(--ink-muted);
  font-size: 11.5px;
  line-height: 1.5;
}

.member-detail__privacy {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #b0b3bd;
  font-size: 10px;
  line-height: 1.5;
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
    padding: 14px 20px 16px;
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
    padding: 0 20px 22px;
  }

  .member-detail__address-text {
    font-size: 20px;
  }
}
</style>
