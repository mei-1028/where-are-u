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
  interpolateTrail,
  trailDistanceMeters,
} from '@/lib/geo'
import {
  addDays,
  dateLabel as formatDateLabel,
  formatClock,
  formatDuration,
  formatKm,
  rangeForDate,
  todayValue,
} from '@/lib/format'
import { usePlaceLabel } from '@/composables/usePlaceLabel'
import Avatar from '@/components/Avatar.vue'
import HistoryMap from '@/components/HistoryMap.vue'
import Icon from '@/components/Icon.vue'
import StateBlock from '@/components/StateBlock.vue'

const route = useRoute()
const router = useRouter()
const family = useFamilyStore()
const ui = useUiStore()

const today = todayValue()
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
const isToday = computed(() => date.value === today)
const nextDisabled = computed(() => date.value >= today)
const pointCountLabel = computed(() => `${points.value.length} 个定位点`)

const currentSample = computed(() => interpolateTrail(points.value, progress.value))
const currentPoint = computed(() => currentSample.value.point)
const { label: currentAddress } = usePlaceLabel(currentPoint, { empty: '暂无轨迹数据' })
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
    <div class="map-card">
      <HistoryMap :points="points" :progress="progress" />

      <div class="map-bar">
        <div class="map-bar__left">
          <button
            type="button"
            class="back-button"
            aria-label="返回家庭地图"
            @click="goBack"
          >
            <Icon name="arrow-left" :size="16" />
          </button>
          <span class="map-bar__back-label">返回地图</span>
          <span class="map-bar__divider" aria-hidden="true" />
          <Avatar :name="nickname" :id="avatarId" :size="30" />
          <span class="map-bar__name">{{ nickname }}</span>
          <div class="date-controls" role="group" aria-label="选择日期">
            <button type="button" class="date-arrow" aria-label="前一天" @click="shiftDate(-1)">
              <Icon name="chevron-left" :size="14" />
            </button>
            <label class="date-chip">
              <Icon name="calendar" :size="13" />
              <span>{{ selectedDateLabel }}</span>
              <input
                type="date"
                class="date-input"
                :value="date"
                :max="today"
                aria-label="选择历史日期"
                @change="onDateInput"
              />
            </label>
            <button
              type="button"
              class="date-arrow"
              aria-label="后一天"
              :disabled="nextDisabled"
              @click="shiftDate(1)"
            >
              <Icon name="chevron-right" :size="14" />
            </button>
          </div>
        </div>

        <div class="map-bar__right">
          <span class="map-bar__meta">
            {{ loading ? '正在加载…' : pointCountLabel }} · {{ startClock }}–{{ endClock }}
          </span>
          <button
            type="button"
            class="btn btn--sm share-btn"
            :disabled="!currentPoint"
            @click="copyCurrentAddress"
          >
            <Icon name="copy" :size="13" />
            复制当前地址
          </button>
        </div>
      </div>

      <aside v-if="points.length && !loading" class="overview-card">
        <div class="overview-head">
          <span class="overview-title">轨迹概览</span>
          <span class="overview-count">{{ pointCountLabel }}</span>
        </div>
        <div class="overview-row">
          <span>里程</span>
          <strong>{{ formatKm(routeDistance) }}</strong>
        </div>
        <div class="overview-row">
          <span>时长</span>
          <strong>{{ formatDuration(routeDuration) }}</strong>
        </div>
        <div class="overview-row">
          <span>首末</span>
          <strong>{{ startClock }} – {{ endClock }}</strong>
        </div>
      </aside>

      <div
        v-if="loading"
        class="history-overlay history-overlay--loading"
        role="status"
      >
        <span class="history-loading-pill">
          <span class="spinner" />
          正在加载轨迹…
        </span>
      </div>

      <div v-else-if="accessDenied" class="history-overlay">
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

      <footer v-if="points.length && !loading" class="timeline-card">
        <div class="timeline-info">
          <span class="timeline-status">
            {{ playing ? '正在回放' : progress >= 1 ? '回放完成' : '已暂停' }}
            <strong class="tabular">{{ currentTimeIso ? formatClock(currentTimeIso) : '—' }}</strong>
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

        <div class="timeline-controls">
          <button
            type="button"
            class="play-button"
            :disabled="!canPlay"
            :aria-label="playing ? '暂停回放' : '播放轨迹'"
            @click="togglePlayback"
          >
            <Icon :name="playing ? 'pause' : 'play'" :size="15" />
          </button>
          <span class="timeline-end tabular">{{ startClock }}</span>
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
          <span class="timeline-end tabular">{{ endClock }}</span>
          <span class="timeline-distance">全程 {{ formatKm(routeDistance) }}</span>
        </div>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.history-page {
  height: calc(100dvh - var(--topbar-h));
  min-height: 0;
  padding: 16px;
  background: var(--canvas);
}

.map-card {
  position: relative;
  height: 100%;
  overflow: hidden;
  border-radius: var(--r-panel-lg);
  background: var(--map-base);
  box-shadow: var(--panel-shadow);
}

/* ---- Floating top bar --------------------------------------------------- */

.map-bar {
  position: absolute;
  top: 16px;
  left: 16px;
  right: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  pointer-events: none;
}

.map-bar__left,
.map-bar__right {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 0 0 1px var(--ring), 0 12px 28px -20px rgba(28, 28, 30, 0.4);
  backdrop-filter: blur(8px);
  pointer-events: auto;
}

.back-button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--surface-soft);
  color: var(--ink);
  transition: background-color 0.14s;
}

.back-button:hover {
  background: var(--surface-sunken);
}

.map-bar__back-label {
  color: var(--slate);
  font-size: 11.5px;
}

.map-bar__divider {
  width: 1px;
  height: 20px;
  background: var(--input-line);
}

.map-bar__name {
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

.date-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 2px;
}

.date-arrow {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: var(--slate);
}

.date-arrow:hover:not(:disabled) {
  background: var(--surface-soft);
  color: var(--ink);
}

.date-arrow:disabled {
  opacity: 0.3;
}

.date-chip {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border-radius: 8px;
  background: var(--surface-soft);
  color: var(--ink);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
}

.date-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.map-bar__meta {
  color: var(--ink-muted);
  font-size: 10.5px;
  white-space: nowrap;
}

.share-btn {
  min-height: 30px;
  padding: 0 10px;
  font-size: 11px;
}

/* ---- Overview ----------------------------------------------------------- */

.overview-card {
  position: absolute;
  top: 76px;
  left: 16px;
  z-index: 25;
  width: 260px;
  padding: 18px;
  border-radius: 18px;
  background: var(--white);
  box-shadow: var(--panel-shadow);
}

.overview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.overview-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.overview-count {
  color: var(--ink-muted);
  font-size: 10.5px;
}

.overview-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
}

.overview-row span {
  color: var(--slate);
  font-size: 11px;
}

.overview-row strong {
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ---- Overlays ----------------------------------------------------------- */

.history-overlay {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(245, 246, 248, 0.55);
  backdrop-filter: blur(2px);
}

.history-overlay--loading {
  z-index: 29;
  padding: 0;
  background: transparent;
  backdrop-filter: none;
  pointer-events: none;
}

.history-loading-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 0 0 1px var(--ring);
  color: var(--slate);
  font-size: 12px;
}

.history-state-card {
  width: min(430px, 100%);
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow: var(--panel-shadow);
}

/* ---- Timeline ----------------------------------------------------------- */

.timeline-card {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 16px;
  z-index: 28;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 20px;
  border-radius: 18px;
  background: var(--white);
  box-shadow: var(--panel-shadow);
}

.timeline-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.timeline-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

.timeline-status strong {
  font-weight: 600;
  color: var(--blue);
}

.timeline-address {
  color: var(--slate);
  font-size: 11.5px;
  border-radius: 5px;
  padding: 2px 4px;
}

.timeline-address:hover:not(:disabled) {
  background: var(--surface-soft);
  color: var(--ink);
}

.speed-group {
  display: inline-flex;
  gap: 2px;
  margin-left: auto;
  padding: 2px;
  border-radius: 9px;
  background: var(--surface-soft);
}

.speed-button {
  height: 24px;
  padding: 0 12px;
  border-radius: 7px;
  color: var(--slate);
  font-size: 11px;
  font-weight: 600;
}

.speed-button.is-active {
  background: var(--white);
  color: var(--blue);
  box-shadow: 0 1px 2px rgba(28, 28, 30, 0.08);
}

.timeline-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.play-button {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--blue);
  color: var(--white);
  transition: background-color 0.16s;
}

.play-button:hover:not(:disabled) {
  background: var(--blue-hover);
}

.play-button:disabled {
  opacity: 0.4;
}

.timeline-end {
  color: var(--ink-muted);
  font-size: 11px;
}

.timeline-distance {
  color: var(--ink-muted);
  font-size: 11px;
  white-space: nowrap;
}

.timeline-range {
  flex: 1 1 auto;
  min-width: 0;
}

@media (max-width: 900px) {
  .history-page {
    padding: 12px;
  }

  .map-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }

  .map-bar__right {
    justify-content: space-between;
  }

  .overview-card {
    display: none;
  }

  .timeline-card {
    left: 10px;
    right: 10px;
    bottom: 10px;
    padding: 12px 14px;
  }

  .timeline-distance {
    display: none;
  }
}
</style>
