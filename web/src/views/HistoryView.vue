<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api'
import { ApiError } from '@/api/errors'
import type { LocationPoint } from '@/api/types'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { copyText } from '@/lib/clipboard'
import { messageForError } from '@/lib/errors'
import {
  clamp,
  describeLocation,
  interpolateTrail,
  trailDistanceMeters,
} from '@/lib/geo'
import {
  addDays,
  dateLabel as formatDateLabel,
  formatClock,
  formatDuration,
  formatKm,
  fullDateLabel,
  rangeForDate,
  todayValue,
} from '@/lib/format'
import Avatar from '@/components/Avatar.vue'
import HistoryMap from '@/components/HistoryMap.vue'
import Icon from '@/components/Icon.vue'
import StateBlock from '@/components/StateBlock.vue'

const route = useRoute()
const router = useRouter()
const family = useFamilyStore()
const ui = useUiStore()

const today = todayValue()
const yesterday = addDays(today, -1)
const SPEEDS = [1, 2, 4] as const
const BASE_DURATION_SECONDS = 32

const deviceId = computed(() => String(route.params.deviceId ?? ''))
const date = ref(today)
const points = ref<LocationPoint[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const accessDenied = ref(false)
const progress = ref(0)
const playing = ref(false)
const speed = ref<number>(1)

let rafId: number | null = null
let lastFrame = 0
let requestSeq = 0

const member = computed(
  () => family.memberViews.find((item) => item.deviceId === deviceId.value) ?? null,
)
const device = computed(
  () => family.devices.find((item) => item.id === deviceId.value) ?? null,
)
const nickname = computed(
  () => member.value?.nickname ?? device.value?.ownerNickname ?? '家人',
)
const avatarId = computed(
  () => member.value?.userId ?? device.value?.userId ?? deviceId.value,
)
const selectedDateLabel = computed(() => formatDateLabel(date.value))
const fullDateText = computed(() => fullDateLabel(date.value))
const isToday = computed(() => date.value === today)
const isYesterday = computed(() => date.value === yesterday)
const nextDisabled = computed(() => date.value >= today)
const pointCountLabel = computed(() => `${points.value.length} 个定位点`)

const currentSample = computed(() => interpolateTrail(points.value, progress.value))
const currentPoint = computed(() => currentSample.value.point)
const currentAddress = computed(() => {
  const point = currentPoint.value
  if (!point) return '暂无轨迹数据'
  return describeLocation(point.lat, point.lng)
})
const currentTimeIso = computed(() => {
  const first = points.value[0]
  const last = points.value[points.value.length - 1]
  if (!first || !last) return null
  const start = new Date(first.recordedAt).getTime()
  const end = new Date(last.recordedAt).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end)) return first.recordedAt
  return new Date(start + (end - start) * progress.value).toISOString()
})
const startClock = computed(() => formatClock(points.value[0]?.recordedAt))
const endClock = computed(() =>
  formatClock(points.value[points.value.length - 1]?.recordedAt),
)
const routeDistance = computed(() => trailDistanceMeters(points.value))
const routeDuration = computed(() => {
  const first = points.value[0]
  const last = points.value[points.value.length - 1]
  if (!first || !last) return 0
  return Math.max(
    0,
    new Date(last.recordedAt).getTime() - new Date(first.recordedAt).getTime(),
  )
})
const canPlay = computed(() => points.value.length > 1 && !loading.value)
const progressPercent = computed(() => Math.round(progress.value * 100))

function stopPlayback(): void {
  playing.value = false
  if (rafId !== null) {
    window.cancelAnimationFrame(rafId)
    rafId = null
  }
  lastFrame = 0
}

function frame(timestamp: number): void {
  if (!playing.value) return
  if (!lastFrame) lastFrame = timestamp
  const delta = Math.min(0.12, (timestamp - lastFrame) / 1000)
  lastFrame = timestamp
  progress.value = Math.min(
    1,
    progress.value + (delta * speed.value) / BASE_DURATION_SECONDS,
  )
  if (progress.value >= 1) {
    playing.value = false
    rafId = null
    lastFrame = 0
    return
  }
  rafId = window.requestAnimationFrame(frame)
}

function togglePlayback(): void {
  if (!canPlay.value) return
  if (playing.value) {
    stopPlayback()
    return
  }
  if (progress.value >= 1) progress.value = 0
  playing.value = true
  lastFrame = 0
  rafId = window.requestAnimationFrame(frame)
}

function setSpeed(next: number): void {
  speed.value = next
}

function onScrub(event: Event): void {
  stopPlayback()
  const value = Number((event.target as HTMLInputElement).value)
  progress.value = clamp(value / 1000, 0, 1)
}

async function loadPoints(): Promise<void> {
  const seq = ++requestSeq
  stopPlayback()
  progress.value = 0
  loading.value = true
  error.value = null
  accessDenied.value = false
  points.value = []
  try {
    const range = rangeForDate(date.value)
    const response = await api.locations.history(deviceId.value, range.from, range.to, 5000)
    if (seq !== requestSeq) return
    points.value = [...response.points].sort(
      (a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime(),
    )
  } catch (err) {
    if (seq !== requestSeq) return
    if (
      err instanceof ApiError &&
      (err.code === 'NOT_FOUND' || err.code === 'FORBIDDEN')
    ) {
      accessDenied.value = true
    } else {
      error.value = messageForError(err, '轨迹加载失败')
    }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

async function bootstrap(): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  error.value = null
  accessDenied.value = false
  points.value = []
  stopPlayback()
  progress.value = 0

  await family.ensureLoaded()
  if (seq !== requestSeq) return

  if (!family.family) {
    loading.value = false
    if (family.error) error.value = family.error
    else accessDenied.value = true
    return
  }

  await loadPoints()
}

async function retry(): Promise<void> {
  if (!family.family) await bootstrap()
  else await loadPoints()
}

function setDate(value: string): void {
  if (!value || value > today || value === date.value) return
  date.value = value
  void loadPoints()
}

function shiftDate(delta: number): void {
  const next = addDays(date.value, delta)
  if (next > today) return
  setDate(next)
}

function onDateInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  if (value) setDate(value)
}

function goBack(): void {
  void router.push('/')
}

async function copyCurrentAddress(): Promise<void> {
  const point = currentPoint.value
  if (!point) return
  const ok = await copyText(`${currentAddress.value}（${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}）`)
  if (ok) ui.toast('当前地址已复制', 'success')
  else ui.toast('复制失败，请长按选择文本', 'error')
}

watch(deviceId, () => {
  void bootstrap()
})

onMounted(() => {
  void bootstrap()
})

onBeforeUnmount(() => {
  requestSeq += 1
  stopPlayback()
})
</script>

<template>
  <div class="history-page">
    <header class="history-head">
      <div class="history-head__identity">
        <button
          type="button"
          class="btn btn--icon btn--ghost back-button"
          aria-label="返回家庭地图"
          @click="goBack"
        >
          <Icon name="arrow-left" :size="19" />
        </button>
        <Avatar :name="nickname" :id="avatarId" :size="46" />
        <div class="history-head__titles">
          <p class="micro">HISTORY · 历史轨迹</p>
          <h1 class="history-title">{{ nickname }}的轨迹</h1>
          <p class="history-subtitle">
            {{ fullDateText }} · {{ loading ? '正在加载…' : pointCountLabel }}
          </p>
        </div>
      </div>

      <div class="history-head__aside">
        <div class="date-nav" role="group" aria-label="选择日期">
          <button
            type="button"
            class="date-nav__arrow"
            aria-label="前一天"
            @click="shiftDate(-1)"
          >
            <Icon name="chevron-left" :size="17" />
          </button>
          <button
            type="button"
            class="date-chip"
            :class="{ 'is-active': isToday }"
            @click="setDate(today)"
          >
            今天
          </button>
          <button
            type="button"
            class="date-chip"
            :class="{ 'is-active': isYesterday }"
            @click="setDate(yesterday)"
          >
            昨天
          </button>
          <input
            type="date"
            class="date-input"
            :value="date"
            :max="today"
            aria-label="选择历史日期"
            @change="onDateInput"
          />
          <button
            type="button"
            class="date-nav__arrow"
            aria-label="后一天"
            :disabled="nextDisabled"
            @click="shiftDate(1)"
          >
            <Icon name="chevron-right" :size="17" />
          </button>
        </div>

        <div v-if="points.length && !loading" class="history-stats">
          <div class="history-stat">
            <span class="micro">里程</span>
            <strong>{{ formatKm(routeDistance) }}</strong>
          </div>
          <div class="history-stat">
            <span class="micro">时长</span>
            <strong>{{ formatDuration(routeDuration) }}</strong>
          </div>
          <div class="history-stat">
            <span class="micro">时间段</span>
            <strong>{{ startClock }} — {{ endClock }}</strong>
          </div>
        </div>
      </div>
    </header>

    <div class="history-map-card">
      <HistoryMap :points="points" :progress="progress" />

      <div v-if="loading" class="history-overlay history-overlay--loading" role="status">
        <span class="history-loading-pill">
          <span class="spinner" />
          正在加载轨迹…
        </span>
      </div>

      <div
        v-else-if="accessDenied"
        class="history-overlay"
      >
        <div class="history-state-card">
          <StateBlock
            tone="danger"
            icon="lock"
            title="无法查看这条轨迹"
            description="该设备不存在、已被解绑，或你已不在这个家庭中。"
            action-label="返回地图"
            secondary-label="重新加载"
            @action="goBack"
            @secondary="retry"
          />
        </div>
      </div>

      <div v-else-if="error" class="history-overlay">
        <div class="history-state-card">
          <StateBlock
            tone="danger"
            icon="alert"
            title="轨迹加载失败"
            :description="error"
            action-label="重试"
            secondary-label="返回地图"
            @action="retry"
            @secondary="goBack"
          />
        </div>
      </div>

      <div v-else-if="!points.length" class="history-overlay">
        <div class="history-state-card">
          <StateBlock
            tone="neutral"
            icon="calendar"
            title="这一天没有轨迹记录"
            description="试试前一天，或确认设备当天已开启位置共享并保持在线。"
            action-label="查看前一天"
            :secondary-label="isToday ? '回到地图' : '回到今天'"
            @action="shiftDate(-1)"
            @secondary="isToday ? goBack() : setDate(today)"
          />
        </div>
      </div>
    </div>

    <footer class="history-timeline">
      <div class="timeline-controls">
        <button
          type="button"
          class="play-button"
          :disabled="!canPlay"
          :aria-label="playing ? '暂停回放' : '播放轨迹'"
          @click="togglePlayback"
        >
          <Icon :name="playing ? 'pause' : 'play'" :size="20" />
        </button>
        <div class="speed-group" role="group" aria-label="回放倍速">
          <button
            v-for="option in SPEEDS"
            :key="option"
            type="button"
            class="speed-button"
            :class="{ 'is-active': speed === option }"
            :aria-pressed="speed === option"
            @click="setSpeed(option)"
          >
            {{ option }}x
          </button>
        </div>
      </div>

      <div class="timeline-track">
        <div class="timeline-caption">
          <span class="timeline-time tabular">
            {{ currentTimeIso ? formatClock(currentTimeIso) : '—' }}
          </span>
          <button
            type="button"
            class="timeline-address"
            :disabled="!currentPoint"
            title="复制当前地址"
            @click="copyCurrentAddress"
          >
            {{ currentAddress }}
          </button>
          <span class="timeline-percent tabular">{{ progressPercent }}%</span>
        </div>
        <input
          class="range timeline-range"
          type="range"
          min="0"
          max="1000"
          step="1"
          :value="Math.round(progress * 1000)"
          :disabled="!points.length || loading"
          :style="{ '--progress': `${progressPercent}%` }"
          :aria-valuetext="currentTimeIso ? formatClock(currentTimeIso) : '无轨迹'"
          aria-label="轨迹回放进度"
          @input="onScrub"
        />
        <div class="timeline-ends">
          <span>{{ startClock }}</span>
          <span class="timeline-ends__hint">
            {{ points.length ? '拖动时间轴查看沿途位置' : '选择日期查看轨迹' }}
          </span>
          <span>{{ endClock }}</span>
        </div>
      </div>

      <div class="timeline-meta">
        <p class="micro">PLAYBACK</p>
        <p class="timeline-meta__date">{{ selectedDateLabel }}</p>
        <p class="timeline-meta__hint">
          {{ playing ? `${speed}x 回放中` : '1x · 2x · 4x' }}
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.history-page {
  display: grid;
  grid-template-rows: auto minmax(280px, 1fr) auto;
  gap: 16px;
  height: calc(100dvh - var(--topbar-h));
  min-height: 0;
  padding: 24px 0 max(20px, env(safe-area-inset-bottom));
  overflow: hidden;
}

.history-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  padding: 0 32px;
}

.history-head__identity {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.back-button {
  flex: 0 0 auto;
  margin-right: 2px;
  color: var(--slate);
}

.back-button:hover {
  color: var(--ink);
}

.history-head__titles {
  min-width: 0;
}

.history-title {
  margin-top: 4px;
  font-size: 30px;
  line-height: 1.18;
  letter-spacing: -0.028em;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-subtitle {
  margin-top: 4px;
  color: var(--ink-muted);
  font-size: 12.5px;
  line-height: 1.5;
}

.history-head__aside {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.date-nav {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: 12px;
  background: var(--white);
  box-shadow: var(--ring-shadow);
}

.date-nav__arrow {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  color: var(--slate);
  transition: background-color 0.14s;
}

.date-nav__arrow:hover:not(:disabled) {
  background: var(--surface-soft);
  color: var(--ink);
}

.date-nav__arrow:disabled {
  opacity: 0.3;
}

.date-chip {
  height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  color: var(--slate);
  font-size: 12.5px;
  font-weight: 500;
  transition:
    background-color 0.14s,
    color 0.14s;
}

.date-chip:hover {
  background: var(--surface-soft);
  color: var(--ink);
}

.date-chip.is-active {
  background: var(--blue-soft);
  color: var(--blue-pressed);
  font-weight: 600;
}

.date-input {
  height: 32px;
  width: 142px;
  padding: 0 8px;
  border: 1px solid var(--input-line);
  border-radius: 8px;
  background: #fff;
  color: var(--ink);
  font-size: 12.5px;
  font-family: var(--font-sans);
}

.date-input:focus {
  outline: none;
  border-color: var(--blue);
  box-shadow: 0 0 0 3px rgba(91, 118, 254, 0.12);
}

.history-stats {
  display: flex;
  align-items: center;
  gap: 20px;
  padding-right: 4px;
}

.history-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.history-stat .micro {
  font-size: 9px;
}

.history-stat strong {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.history-map-card {
  position: relative;
  min-height: 0;
  margin: 0 32px;
  border-radius: var(--r-panel);
  overflow: hidden;
  box-shadow: var(--ring-shadow);
  background: var(--map-base);
}

.history-overlay {
  position: absolute;
  inset: 0;
  z-index: 6;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(245, 246, 248, 0.62);
  backdrop-filter: blur(2px);
}

.history-overlay--loading {
  background: transparent;
  backdrop-filter: none;
  pointer-events: none;
}

.history-loading-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 15px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--ring);
  color: var(--slate);
  font-size: 12px;
  box-shadow: 0 6px 24px -18px rgba(28, 28, 30, 0.4);
}

.history-state-card {
  width: min(430px, 100%);
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
  pointer-events: auto;
}

.history-timeline {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  margin: 0 32px;
  padding: 16px 22px;
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow: var(--ring-shadow);
}

.timeline-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.play-button {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--blue);
  color: #fff;
  transition:
    background-color 0.16s,
    transform 0.12s;
}

.play-button:hover:not(:disabled) {
  background: var(--blue-pressed);
}

.play-button:active:not(:disabled) {
  transform: scale(0.96);
}

.play-button:disabled {
  background: #eceef3;
  color: #a5a8b5;
}

.speed-group {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: var(--surface-soft);
}

.speed-button {
  min-width: 30px;
  height: 26px;
  padding: 0 6px;
  border-radius: 6px;
  color: var(--ink-muted);
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  transition:
    background-color 0.14s,
    color 0.14s;
}

.speed-button:hover {
  color: var(--ink);
}

.speed-button.is-active {
  background: #fff;
  color: var(--ink);
  box-shadow: 0 1px 3px rgba(28, 28, 30, 0.08);
}

.timeline-track {
  min-width: 0;
}

.timeline-caption {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.timeline-time {
  flex: 0 0 auto;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.timeline-address {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--slate);
  font-size: 13px;
  text-align: left;
  border-radius: 4px;
  padding: 1px 3px;
}

.timeline-address:hover:not(:disabled) {
  background: var(--surface-soft);
  color: var(--ink);
}

.timeline-address:disabled {
  color: var(--ink-muted);
}

.timeline-percent {
  margin-left: auto;
  flex: 0 0 auto;
  color: var(--ink-muted);
  font-size: 11px;
}

.timeline-range {
  margin-top: 2px;
}

.timeline-ends {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--ink-muted);
  font-size: 10px;
  line-height: 1.4;
  font-variant-numeric: tabular-nums;
}

.timeline-ends__hint {
  color: #b0b3bd;
  letter-spacing: 0.01em;
}

.timeline-meta {
  min-width: 130px;
  text-align: right;
}

.timeline-meta__date {
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.timeline-meta__hint {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 10.5px;
}

@media (max-width: 1100px) {
  .history-head {
    align-items: flex-start;
  }

  .history-head__aside {
    align-items: flex-start;
  }

  .history-stats {
    padding-left: 4px;
  }

  .timeline-meta {
    display: none;
  }

  .history-timeline {
    grid-template-columns: auto minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .history-page {
    display: flex;
    flex-direction: column;
    height: auto;
    min-height: calc(100dvh - var(--topbar-h));
    padding: 18px 0 28px;
    overflow: visible;
    gap: 14px;
  }

  .history-head {
    padding: 0 18px;
    gap: 16px;
  }

  .history-map-card {
    margin: 0 18px;
    height: 360px;
    flex: 0 0 auto;
  }

  .history-timeline {
    margin: 0 18px;
    padding: 14px 16px;
    gap: 14px;
    grid-template-columns: 1fr;
  }

  .timeline-controls {
    justify-content: space-between;
  }

  .timeline-caption {
    margin-top: 4px;
  }

  .history-head__identity {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .history-head {
    padding: 0 16px;
  }

  .history-title {
    font-size: 25px;
  }

  .history-map-card {
    margin: 0 12px;
    height: 320px;
  }

  .history-timeline {
    margin: 0 12px;
    border-radius: 16px;
  }

  .date-nav {
    width: 100%;
    justify-content: space-between;
  }

  .date-input {
    width: 132px;
  }

  .history-stats {
    width: 100%;
    justify-content: space-between;
    gap: 10px;
  }

  .history-overlay {
    padding: 14px;
  }

  .history-loading-pill {
    font-size: 11.5px;
  }
}
</style>
