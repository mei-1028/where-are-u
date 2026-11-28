<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { MemberView, PlaceInfo, PlaceType } from '@/api/types'
import { isAmapConfigured, loadAMap } from '@/lib/amap'
import {
  clamp,
  fitMapViewBox,
  isStaleLocation,
  projectToScreen,
} from '@/lib/geo'
import { avatarTone, initials, relativeTime } from '@/lib/format'
import Icon from './Icon.vue'
import SchematicBase from './SchematicBase.vue'

const props = withDefaults(
  defineProps<{
    members: MemberView[]
    selectedId?: string | null
    currentUserId?: string | null
    locatingUserId?: string | null
    drawerOpen?: boolean
    places?: PlaceInfo[]
  }>(),
  {
    selectedId: null,
    currentUserId: null,
    locatingUserId: null,
    drawerOpen: false,
    places: () => [],
  },
)

const emit = defineEmits<{
  select: [userId: string | null]
  /** Map coordinate picked by long-press (touch) or right-click (desktop). */
  longpress: [lat: number, lng: number]
  placeClick: [placeId: string]
}>()

type MapMode = 'schematic' | 'loading' | 'amap'

/** Built-in AMap JS API 2.0 styles (mapStyle: amap://styles/<id>). */
const AMAP_STYLES = [
  { id: 'whitesmoke', label: '远山黛' },
  { id: 'normal', label: '默认' },
  { id: 'light', label: '月光银' },
  { id: 'fresh', label: '草色青' },
  { id: 'grey', label: '雅士灰' },
  { id: 'blue', label: '靛青蓝' },
  { id: 'dark', label: '幻影黑' },
  { id: 'darkblue', label: '极夜蓝' },
  { id: 'wine', label: '酱籽' },
  { id: 'graffiti', label: '涂鸦' },
  { id: 'macaron', label: '马卡龙' },
] as const

const MAP_STYLE_KEY = 'wa_map_style'
const DEFAULT_MAP_STYLE = 'whitesmoke'

function readStoredMapStyle(): string {
  try {
    const stored = window.localStorage.getItem(MAP_STYLE_KEY)
    if (stored && AMAP_STYLES.some((item) => item.id === stored)) return stored
  } catch {
    /* storage can be unavailable in private mode */
  }
  return DEFAULT_MAP_STYLE
}

const mode = ref<MapMode>(isAmapConfigured ? 'loading' : 'schematic')
const amapError = ref('')
const mapStyle = ref(readStoredMapStyle())
const currentStyleLabel = computed(
  () => AMAP_STYLES.find((item) => item.id === mapStyle.value)?.label ?? '默认',
)
const viewport = ref<HTMLElement | null>(null)
const amapEl = ref<HTMLElement | null>(null)
const size = ref({ w: 0, h: 0 })
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const dragging = ref(false)

let resizeObserver: ResizeObserver | null = null
let map: any = null
let amapMarkers: any[] = []
let amapOverlays: any[] = []
let amapPlaceOverlays: any[] = []
let amapPlaceMarkers: any[] = []
let lastMarkerClickAt = 0
let initialFitDone = false
let suppressClick = false
let suppressTimer = 0
let longPressTimer = 0
let longPressAt = 0
let touchOrigin: { x: number; y: number } | null = null

interface DragState {
  pointerId: number
  startX: number
  startY: number
  originX: number
  originY: number
  moved: boolean
}

let drag: DragState | null = null

const fit = computed(() => fitMapViewBox(size.value.w, size.value.h))

const badgeLabel = computed(() => {
  if (mode.value === 'amap') return '高德地图 · 2.0'
  if (mode.value === 'loading') return '正在加载高德地图…'
  if (amapError.value) return '示意地图 · 高德未加载'
  return '示意地图 · 离线可用'
})

const badgeTitle = computed(() => {
  if (amapError.value) return `${amapError.value}，已切换到本地示意底图`
  if (mode.value === 'schematic') return '未配置高德 Key，使用本地示意底图（无需网络）'
  return badgeLabel.value
})

interface DisplayMarker {
  member: MemberView
  left: number
  top: number
  accuracy: number
  selected: boolean
  offline: boolean
  paused: boolean
  locating: boolean
  stale: boolean
  isSelf: boolean
  tone: { bg: string; ink: string }
  timeLabel: string
  label: string
}

const markers = computed<DisplayMarker[]>(() => {
  const currentFit = fit.value
  return props.members.flatMap((member) => {
    const loc = member.lastLocation
    if (!loc) return []
    const point = projectToScreen(loc.lat, loc.lng, currentFit)
    const selected = props.selectedId === member.userId
    const locating = props.locatingUserId === member.userId
    const paused = member.sharingPaused
    const offline = !member.online
    const timeLabel = paused ? '已暂停共享' : relativeTime(loc.recordedAt)
    const label = `${member.nickname}${member.userId === props.currentUserId ? '（我）' : ''}，${
      paused ? '已暂停共享' : offline ? '离线' : '在线'
    }，${timeLabel}`
    return [
      {
        member,
        left: point.x,
        top: point.y,
        accuracy: clamp((loc.accuracy ?? 20) * 2.4, 26, 130),
        selected,
        offline,
        paused,
        locating,
        stale: isStaleLocation(loc),
        isSelf: member.userId === props.currentUserId,
        tone: avatarTone(member.userId),
        timeLabel,
        label,
      },
    ]
  })
})

const layerStyle = computed(() => ({
  transform: `translate3d(${pan.value.x}px, ${pan.value.y}px, 0) scale(${zoom.value})`,
}))

function clampPan(next: { x: number; y: number }, scale: number): { x: number; y: number } {
  const width = size.value.w
  const height = size.value.h
  return {
    x: clamp(next.x, width * (1 - scale), 0),
    y: clamp(next.y, height * (1 - scale), 0),
  }
}

function setZoom(next: number): void {
  const clamped = clamp(next, 1, 2.2)
  if (Math.abs(clamped - zoom.value) < 0.001) return
  const cx = size.value.w / 2
  const cy = size.value.h / 2
  const ratio = clamped / zoom.value
  const nextPan = {
    x: cx - (cx - pan.value.x) * ratio,
    y: cy - (cy - pan.value.y) * ratio,
  }
  zoom.value = clamped
  pan.value = clampPan(nextPan, clamped)
}

function zoomIn(): void {
  if (mode.value === 'amap') {
    map?.zoomIn()
    return
  }
  setZoom(zoom.value * 1.25)
}

function zoomOut(): void {
  if (mode.value === 'amap') {
    map?.zoomOut()
    return
  }
  setZoom(zoom.value / 1.25)
}

function fitAll(): void {
  if (mode.value === 'amap') {
    if (!map) return
    const fitTargets = amapMarkers.length > 0 ? amapMarkers : amapPlaceMarkers
    if (fitTargets.length > 1) {
      map.setFitView(fitTargets, false, [90, 90, 160, 90])
    } else if (fitTargets.length === 1) {
      map.setZoomAndCenter(15, fitTargets[0].getPosition())
    } else {
      map.setZoomAndCenter(13.2, [120.1551, 30.2741])
    }
    return
  }
  zoom.value = 1
  pan.value = { x: 0, y: 0 }
}

function focusSchematic(member: MemberView): void {
  const target = markers.value.find((marker) => marker.member.userId === member.userId)
  if (!target) return
  const nextZoom = Math.max(zoom.value, 1.25)
  const cx = size.value.w * 0.44
  const cy = size.value.h * 0.46
  const nextPan = {
    x: cx - target.left * nextZoom,
    y: cy - target.top * nextZoom,
  }
  zoom.value = nextZoom
  pan.value = clampPan(nextPan, nextZoom)
}

function focusMember(): void {
  if (!props.selectedId) return
  const member = props.members.find((item) => item.userId === props.selectedId)
  if (!member?.lastLocation) return
  if (mode.value === 'amap' && map) {
    map.panTo([member.lastLocation.lng, member.lastLocation.lat])
  } else if (mode.value === 'schematic') {
    focusSchematic(member)
  }
}

function onPointerDown(event: PointerEvent): void {
  if (mode.value !== 'schematic' || zoom.value <= 1.001) return
  const target = event.target as HTMLElement
  if (target.closest('.map-marker, .map-controls, .map-badge, .map-legend')) return
  drag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    originX: pan.value.x,
    originY: pan.value.y,
    moved: false,
  }
  dragging.value = true
  viewport.value?.setPointerCapture(event.pointerId)
  event.preventDefault()
}

function onPointerMove(event: PointerEvent): void {
  if (!drag || drag.pointerId !== event.pointerId) return
  const dx = event.clientX - drag.startX
  const dy = event.clientY - drag.startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) drag.moved = true
  pan.value = clampPan(
    { x: drag.originX + dx, y: drag.originY + dy },
    zoom.value,
  )
}

function onPointerUp(event: PointerEvent): void {
  if (!drag || drag.pointerId !== event.pointerId) return
  suppressClick = drag.moved
  dragging.value = false
  drag = null
  if (viewport.value?.hasPointerCapture(event.pointerId)) {
    viewport.value.releasePointerCapture(event.pointerId)
  }
  window.clearTimeout(suppressTimer)
  suppressTimer = window.setTimeout(() => {
    suppressClick = false
  }, 80)
}

function onBackgroundClick(event: MouseEvent): void {
  if (suppressClick) {
    suppressClick = false
    return
  }
  const target = event.target as HTMLElement
  if (target.closest('.map-marker, .map-controls, .map-badge, .map-legend')) return
  emit('select', null)
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => {
    switch (char) {
      case '&':
        return '&amp;'
      case '<':
        return '&lt;'
      case '>':
        return '&gt;'
      case '"':
        return '&quot;'
      default:
        return '&#39;'
    }
  })
}

const PLACE_TONES: Record<PlaceType, { bg: string; ink: string }> = {
  home: { bg: '#C3FAF5', ink: '#0F5F5A' },
  school: { bg: '#E9EDFF', ink: '#2A41B6' },
  company: { bg: '#FFE6CD', ink: '#8A5A16' },
  other: { bg: '#FDE0F0', ink: '#7A2A5C' },
}

function amapPlaceHtml(place: PlaceInfo): string {
  const tone = PLACE_TONES[place.type] ?? PLACE_TONES.other
  return `<div class="amap-place" style="--place-bg:${tone.bg};--place-ink:${tone.ink}">
    <span class="amap-place__dot"></span>
    <span class="amap-place__name">${escapeHtml(place.name)}</span>
  </div>`
}

function syncPlaceOverlays(): void {
  if (!map || mode.value !== 'amap' || !window.AMap) return
  const AMap = window.AMap
  if (amapPlaceOverlays.length) {
    map.remove(amapPlaceOverlays)
    amapPlaceOverlays = []
    amapPlaceMarkers = []
  }
  if (props.places.length === 0) return

  const overlays: any[] = []
  const markers: any[] = []
  for (const place of props.places) {
    const circle = new AMap.Circle({
      center: [place.lng, place.lat],
      radius: place.radiusMeters,
      strokeColor: '#1BAFA6',
      strokeOpacity: 0.55,
      strokeWeight: 1.5,
      fillColor: '#1BAFA6',
      fillOpacity: 0.06,
      zIndex: 15,
    })
    overlays.push(circle)

    const marker = new AMap.Marker({
      position: [place.lng, place.lat],
      content: amapPlaceHtml(place),
      anchor: 'center',
      zIndex: 90,
    })
    marker.on('click', () => {
      lastMarkerClickAt = Date.now()
      emit('placeClick', place.id)
    })
    overlays.push(marker)
    markers.push(marker)
  }
  map.add(overlays)
  amapPlaceOverlays = overlays
  amapPlaceMarkers = markers
}

function cancelLongPress(): void {
  if (longPressTimer) {
    window.clearTimeout(longPressTimer)
    longPressTimer = 0
  }
  touchOrigin = null
}

function beginLongPress(clientX: number, clientY: number): void {
  cancelLongPress()
  if (mode.value !== 'amap' || !map || !amapEl.value) return
  const rect = amapEl.value.getBoundingClientRect()
  const x = clientX - rect.left
  const y = clientY - rect.top
  touchOrigin = { x: clientX, y: clientY }
  longPressTimer = window.setTimeout(() => {
    longPressTimer = 0
    if (mode.value !== 'amap' || !map) return
    const AMap = window.AMap
    if (!AMap) return
    const lngLat = map.containerToLngLat(new AMap.Pixel(x, y))
    if (!lngLat) return
    longPressAt = Date.now()
    // Suppress the synthetic click that follows touchend.
    suppressClick = true
    window.clearTimeout(suppressTimer)
    suppressTimer = window.setTimeout(() => {
      suppressClick = false
    }, 500)
    emit('longpress', Number(lngLat.getLat()), Number(lngLat.getLng()))
  }, 600)
}

function onTouchStart(event: TouchEvent): void {
  if (event.touches.length !== 1) {
    cancelLongPress()
    return
  }
  const touch = event.touches[0]!
  beginLongPress(touch.clientX, touch.clientY)
}

function onTouchMove(event: TouchEvent): void {
  if (!touchOrigin || event.touches.length !== 1) return
  const touch = event.touches[0]!
  if (
    Math.abs(touch.clientX - touchOrigin.x) > 12 ||
    Math.abs(touch.clientY - touchOrigin.y) > 12
  ) {
    cancelLongPress()
  }
}

function amapBubbleHtml(member: MemberView): string {
  const tone = avatarTone(member.userId)
  const selected = props.selectedId === member.userId
  const paused = member.sharingPaused
  const offline = !member.online
  const timeLabel = paused ? '已暂停共享' : relativeTime(member.lastLocation?.recordedAt)
  const statusClass = paused
    ? 'is-paused'
    : member.userId === props.locatingUserId
      ? 'is-locating'
      : offline
        ? 'is-offline'
        : 'is-online'
  return `<div class="amap-bubble${selected ? ' is-selected' : ''}${
    offline ? ' is-offline' : ''
  }" style="--bubble-bg:${tone.bg};--bubble-ink:${tone.ink}">
    <div class="amap-bubble__avatar">${escapeHtml(initials(member.nickname))}
      <span class="amap-bubble__status ${statusClass}"></span>
    </div>
    <div class="amap-bubble__label">
      <span class="amap-bubble__name">${escapeHtml(member.nickname)}</span>
      <span class="amap-bubble__time">${escapeHtml(timeLabel)}</span>
    </div>
  </div>`
}

function syncAmapMarkers(): void {
  if (!map || mode.value !== 'amap' || !window.AMap) return
  const AMap = window.AMap
  if (amapOverlays.length) {
    map.remove(amapOverlays)
    amapOverlays = []
    amapMarkers = []
  }
  const overlays: any[] = []
  const markerList: any[] = []

  for (const member of props.members) {
    const loc = member.lastLocation
    if (!loc) continue
    const selected = props.selectedId === member.userId
    const marker = new AMap.Marker({
      position: [loc.lng, loc.lat],
      content: amapBubbleHtml(member),
      anchor: 'center',
      zIndex: selected ? 200 : member.userId === props.locatingUserId ? 180 : 120,
    })
    marker.on('click', () => {
      lastMarkerClickAt = Date.now()
      emit('select', member.userId)
    })
    markerList.push(marker)
    overlays.push(marker)

    if (loc.accuracy) {
      const circle = new AMap.Circle({
        center: [loc.lng, loc.lat],
        radius: Math.max(12, loc.accuracy),
        strokeColor: selected ? '#5B76FE' : '#8A8F9C',
        strokeOpacity: selected ? 0.4 : 0.2,
        strokeWeight: 1,
        fillColor: '#5B76FE',
        fillOpacity: selected ? 0.08 : 0.04,
        zIndex: 20,
      })
      overlays.push(circle)
    }
  }

  if (overlays.length) map.add(overlays)
  amapOverlays = overlays
  amapMarkers = markerList

  if (!initialFitDone && markerList.length) {
    initialFitDone = true
    // AMap needs a tick to lay out freshly added markers; fitting now can be a
    // no-op and leave the camera at the default Hangzhou center.
    window.setTimeout(() => {
      if (!map || mode.value !== 'amap') return
      if (markerList.length > 1) {
        map.setFitView(markerList, true, [90, 90, 160, 90])
      } else {
        map.setZoomAndCenter(15, markerList[0].getPosition())
      }
    }, 60)
  }
}

function applyMapStyle(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  if (!AMAP_STYLES.some((item) => item.id === value)) return
  mapStyle.value = value
  try {
    window.localStorage.setItem(MAP_STYLE_KEY, value)
  } catch {
    /* ignore quota/private-mode errors */
  }
  if (mode.value === 'amap' && map) {
    map.setMapStyle?.(`amap://styles/${value}`)
  }
}

async function initAmap(): Promise<void> {
  if (!isAmapConfigured) return
  try {
    const AMap = await loadAMap()
    await nextTick()
    if (!amapEl.value) throw new Error('地图容器未就绪')
    map = new AMap.Map(amapEl.value, {
      zoom: 13.2,
      center: [120.1551, 30.2741],
      viewMode: '2D',
      mapStyle: `amap://styles/${mapStyle.value}`,
      showLabel: true,
      resizeEnable: true,
    })
    map.on('click', () => {
      if (Date.now() - longPressAt < 600) return
      if (Date.now() - lastMarkerClickAt > 250) emit('select', null)
    })
    map.on('rightclick', (event: any) => {
      const lngLat = event?.lnglat
      if (!lngLat) return
      longPressAt = Date.now()
      emit('longpress', Number(lngLat.getLat()), Number(lngLat.getLng()))
    })
    mode.value = 'amap'
    await nextTick()
    syncAmapMarkers()
    syncPlaceOverlays()
    map.resize?.()
  } catch (err) {
    amapError.value = err instanceof Error ? err.message : '高德地图加载失败'
    mode.value = 'schematic'
  }
}

watch(
  () => props.members,
  () => {
    if (mode.value === 'amap') syncAmapMarkers()
  },
  { deep: true },
)

watch(
  () => props.places,
  () => {
    if (mode.value === 'amap') syncPlaceOverlays()
  },
  { deep: true },
)

watch(
  () => props.selectedId,
  () => {
    if (mode.value === 'amap') {
      syncAmapMarkers()
      focusMember()
    } else {
      focusMember()
    }
  },
)

watch(size, () => {
  if (zoom.value === 1) pan.value = { x: 0, y: 0 }
  else pan.value = clampPan(pan.value, zoom.value)
  if (mode.value === 'amap') map?.resize?.()
})

onMounted(() => {
  const el = viewport.value
  if (!el) return
  const measure = () => {
    const rect = el.getBoundingClientRect()
    size.value = { w: rect.width, h: rect.height }
  }
  measure()
  resizeObserver = new ResizeObserver(measure)
  resizeObserver.observe(el)
  if (isAmapConfigured) void initAmap()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.clearTimeout(suppressTimer)
  if (map) {
    map.destroy?.()
    map = null
  }
})
</script>

<template>
  <div
    ref="viewport"
    class="map-viewport"
    :class="{
      'is-dragging': dragging,
      'is-pannable': mode === 'schematic' && zoom > 1.001,
    }"
    role="region"
    aria-label="家庭位置地图"
    @click="onBackgroundClick"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @touchstart="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="cancelLongPress"
    @touchcancel="cancelLongPress"
    @contextmenu.prevent
  >
    <div ref="amapEl" class="amap-layer" :class="{ 'is-visible': mode === 'amap' }" />

    <div v-show="mode !== 'amap'" class="schematic-layer" :style="layerStyle">
      <SchematicBase
        :preserve="fit.stretch ? 'none' : 'meet'"
        :hide-labels="fit.stretch && size.w < 900"
      />

      <button
        v-for="marker in markers"
        :key="marker.member.userId"
        type="button"
        class="map-marker"
        :class="{
          'is-selected': marker.selected,
          'is-offline': marker.offline,
          'is-paused': marker.paused,
          'is-locating': marker.locating,
          'is-stale': marker.stale,
        }"
        :style="{
          left: `${marker.left}px`,
          top: `${marker.top}px`,
          zIndex: marker.selected ? 50 : marker.locating ? 40 : 20,
        }"
        :aria-label="marker.label"
        :aria-pressed="marker.selected"
        @click.stop="emit('select', marker.member.userId)"
      >
        <span
          v-if="marker.locating"
          class="map-marker__pulse"
          aria-hidden="true"
        />
        <span
          class="map-marker__accuracy"
          aria-hidden="true"
          :style="{
            width: `${marker.accuracy}px`,
            height: `${marker.accuracy}px`,
          }"
        />
        <span
          class="map-marker__avatar"
          aria-hidden="true"
          :style="{ background: marker.tone.bg, color: marker.tone.ink }"
        >
          {{ initials(marker.member.nickname) }}
          <span
            class="map-marker__status status-dot"
            :class="
              marker.paused
                ? 'status-dot--paused'
                : marker.locating
                  ? 'status-dot--locating'
                  : marker.offline
                    ? 'status-dot--offline'
                    : 'status-dot--online'
            "
          />
        </span>
        <span class="map-marker__label" aria-hidden="true">
          <span class="map-marker__name">{{ marker.member.nickname }}</span>
          <span v-if="marker.isSelf" class="map-marker__me">我</span>
          <span class="map-marker__time">{{ marker.timeLabel }}</span>
        </span>
      </button>
    </div>

    <div v-if="!drawerOpen" class="map-badge" :title="badgeTitle" aria-live="polite">
      <span v-if="mode === 'loading'" class="spinner" />
      <Icon v-else name="map" :size="14" />
      <span>{{ badgeLabel }}</span>
    </div>

    <label
      v-if="mode === 'amap' && !drawerOpen"
      class="map-style"
      title="切换高德地图配色"
    >
      <Icon name="palette" :size="13" />
      <span class="map-style__label">{{ currentStyleLabel }}</span>
      <Icon name="chevron-down" :size="12" class="map-style__chevron" />
      <select
        class="map-style__select"
        :value="mapStyle"
        aria-label="高德地图配色"
        @change="applyMapStyle"
      >
        <option v-for="item in AMAP_STYLES" :key="item.id" :value="item.id">
          {{ item.label }}
        </option>
      </select>
    </label>

    <div class="map-legend" aria-hidden="true">
      <span class="map-legend__item"><i class="status-dot status-dot--online" />在线</span>
      <span class="map-legend__item"><i class="status-dot status-dot--offline" />离线</span>
      <span class="map-legend__item"><i class="status-dot status-dot--locating" />定位中</span>
      <span v-if="mode !== 'amap'" class="map-legend__scale">
        <span class="map-legend__bar" />
        500 m
      </span>
    </div>

    <div
      class="map-controls"
      :class="{ 'map-controls--shifted': drawerOpen }"
      role="group"
      aria-label="地图控件"
      @click.stop
      @pointerdown.stop
    >
      <div class="map-zoom">
        <button
          type="button"
          class="map-zoom__button"
          aria-label="放大地图"
          :disabled="mode === 'schematic' && zoom >= 2.2"
          @click="zoomIn"
        >
          <Icon name="plus" :size="17" />
        </button>
        <span class="map-zoom__divider" aria-hidden="true" />
        <button
          type="button"
          class="map-zoom__button"
          aria-label="缩小地图"
          :disabled="mode === 'schematic' && zoom <= 1"
          @click="zoomOut"
        >
          <Icon name="minus" :size="17" />
        </button>
      </div>
      <button type="button" class="map-fit" aria-label="查看全家位置" @click="fitAll">
        <Icon name="maximize" :size="15" />
        查看全家
      </button>
    </div>
  </div>
</template>

<style scoped>
.map-viewport {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: var(--map-base);
  background-image: radial-gradient(circle at 1px 1px, #e5e7ec 1.1px, transparent 1.2px);
  background-size: 26px 26px;
  touch-action: pan-y;
  user-select: none;
}

.map-viewport.is-pannable {
  cursor: grab;
  touch-action: none;
}

.map-viewport.is-dragging {
  cursor: grabbing;
}

.amap-layer {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.35s ease;
}

.amap-layer.is-visible {
  opacity: 1;
  pointer-events: auto;
}

.schematic-layer {
  position: absolute;
  inset: 0;
  transform-origin: 0 0;
  will-change: transform;
}

.schematic-layer :deep(svg) {
  width: 100%;
  height: 100%;
}

/* ---- Markers ------------------------------------------------------------ */

.map-marker {
  position: absolute;
  width: 0;
  height: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
  overflow: visible;
}

.map-marker:focus-visible {
  outline: none;
}

.map-marker:focus-visible .map-marker__avatar {
  outline: 2px solid var(--blue);
  outline-offset: 3px;
}

.map-marker__accuracy {
  position: absolute;
  left: 0;
  top: 0;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  border: 1px solid rgba(91, 118, 254, 0.22);
  background: rgba(91, 118, 254, 0.07);
  pointer-events: none;
  transition:
    width 0.3s var(--ease-out),
    height 0.3s var(--ease-out);
}

.map-marker.is-selected .map-marker__accuracy {
  border-color: rgba(91, 118, 254, 0.4);
  background: rgba(91, 118, 254, 0.1);
}

.map-marker__avatar {
  position: absolute;
  left: 0;
  top: 0;
  transform: translate(-50%, -50%);
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 3px solid #fff;
  box-shadow:
    0 0 0 1px rgba(28, 28, 30, 0.08),
    var(--marker-shadow);
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
  transition:
    transform 0.2s var(--ease-out),
    opacity 0.2s;
}

.map-marker__status {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 11px;
  height: 11px;
  border: 2px solid #fff;
}

.map-marker__label {
  position: absolute;
  left: 0;
  top: 27px;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(224, 226, 232, 0.9);
  box-shadow: 0 3px 10px -6px rgba(28, 28, 30, 0.3);
  color: var(--ink);
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
  backdrop-filter: blur(4px);
  transition:
    border-color 0.16s,
    background-color 0.16s;
}

.map-marker__time {
  color: var(--ink-muted);
  font-size: 10px;
  font-weight: 500;
}

.map-marker__me {
  display: inline-flex;
  align-items: center;
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--blue-soft);
  color: var(--blue-pressed);
  font-size: 9px;
  font-weight: 600;
}

.map-marker.is-selected .map-marker__avatar {
  transform: translate(-50%, -50%) scale(1.08);
  box-shadow:
    0 0 0 3px var(--blue),
    0 10px 26px -12px rgba(42, 65, 182, 0.55);
}

.map-marker.is-selected .map-marker__label {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}

.map-marker.is-selected .map-marker__time {
  color: rgba(255, 255, 255, 0.65);
}

.map-marker.is-selected .map-marker__me {
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
}

.map-marker.is-offline .map-marker__avatar {
  opacity: 0.58;
  filter: saturate(0.6);
}

.map-marker.is-offline .map-marker__label {
  opacity: 0.82;
}

.map-marker.is-stale .map-marker__avatar::after {
  content: '';
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 1.5px dashed #b9bcc6;
}

.map-marker.is-paused .map-marker__avatar {
  opacity: 0.72;
}

.map-marker__pulse {
  position: absolute;
  left: 0;
  top: 0;
  width: 44px;
  height: 44px;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  border: 2px solid #f0a23a;
  pointer-events: none;
  animation: marker-pulse 1.6s var(--ease-out) infinite;
}

@keyframes marker-pulse {
  0% {
    transform: translate(-50%, -50%) scale(0.9);
    opacity: 0.85;
  }
  100% {
    transform: translate(-50%, -50%) scale(1.9);
    opacity: 0;
  }
}

/* ---- Overlays ----------------------------------------------------------- */

.map-badge {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 30;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 11px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--ring);
  box-shadow: 0 3px 12px -10px rgba(28, 28, 30, 0.3);
  color: var(--slate);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.01em;
  pointer-events: none;
  backdrop-filter: blur(6px);
}

.map-badge .spinner {
  width: 12px;
  height: 12px;
  border-width: 1.5px;
}

.map-style {
  position: absolute;
  top: 56px;
  right: 16px;
  z-index: 31;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--ring);
  color: var(--slate);
  font-size: 10.5px;
  font-weight: 500;
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition: background-color 0.16s;
}

.map-style:hover {
  background: var(--white);
}

.map-style__label {
  color: var(--ink);
}

.map-style__chevron {
  color: var(--ink-muted);
}

.map-style__select {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
  font-size: 16px;
}

.map-legend {
  position: absolute;
  left: 16px;
  top: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 30px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--ring);
  color: var(--ink-muted);
  font-size: 10.5px;
  font-weight: 500;
  pointer-events: none;
  backdrop-filter: blur(6px);
}

.map-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.map-legend__item .status-dot {
  width: 6px;
  height: 6px;
  box-shadow: none;
}

.map-legend__scale {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-left: 10px;
  border-left: 1px solid var(--ring);
  white-space: nowrap;
}

.map-legend__bar {
  display: inline-block;
  width: 30px;
  height: 5px;
  border-left: 1px solid #a5a8b5;
  border-right: 1px solid #a5a8b5;
  border-bottom: 1px solid #a5a8b5;
}

.map-controls {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 35;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  transition: right 0.24s var(--ease-out);
}

.map-controls--shifted {
  right: 392px;
}

.map-zoom {
  display: flex;
  flex-direction: column;
  align-items: center;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--ring);
  box-shadow: 0 6px 20px -16px rgba(28, 28, 30, 0.35);
  overflow: hidden;
}

.map-zoom__button {
  display: grid;
  place-items: center;
  width: 38px;
  height: 36px;
  color: var(--slate);
  transition: background-color 0.14s;
}

.map-zoom__button:hover:not(:disabled) {
  background: var(--surface-soft);
  color: var(--ink);
}

.map-zoom__button:disabled {
  opacity: 0.3;
}

.map-zoom__divider {
  width: 22px;
  height: 1px;
  background: var(--line);
}

.map-fit {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: #fff;
  border: 1px solid var(--ring);
  color: var(--slate);
  font-size: 11.5px;
  font-weight: 500;
  box-shadow: 0 6px 20px -16px rgba(28, 28, 30, 0.35);
  transition:
    background-color 0.14s,
    color 0.14s;
}

.map-fit:hover {
  background: var(--surface-soft);
  color: var(--ink);
}

@media (max-width: 1024px) {
  .map-style {
    top: 56px;
    right: 12px;
  }

  /* The member panel becomes an overlay with a toggle at the top-left. */
  .map-legend {
    top: auto;
    bottom: 16px;
  }
}

@media (max-width: 900px) {
  .map-controls {
    right: 12px;
    bottom: 12px;
  }

  .map-controls--shifted {
    right: 12px;
    opacity: 0;
    pointer-events: none;
  }

  .map-legend {
    left: 12px;
    top: auto;
    bottom: 12px;
    gap: 9px;
    padding: 0 10px;
  }

  .map-legend__scale {
    display: none;
  }

  .map-badge {
    top: 12px;
    right: 12px;
  }
}

@media (max-width: 480px) {
  .map-legend {
    top: auto;
    bottom: 64px;
    font-size: 10px;
    gap: 8px;
  }

  .map-fit {
    display: none;
  }

  .map-controls {
    bottom: 14px;
  }
}
</style>

<style>
/* AMap injects marker DOM outside the Vue scoped-style tree, so these styles
   are intentionally global but namespaced with .amap-bubble / .amap-place. */
.amap-place {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 9px 4px 7px;
  border-radius: 999px;
  background: var(--place-bg, #fde0f0);
  color: var(--place-ink, #7a2a5c);
  border: 2px solid #fff;
  box-shadow:
    0 0 0 1px rgba(28, 28, 30, 0.06),
    0 4px 12px -6px rgba(28, 28, 30, 0.35);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.amap-place__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.7;
}

.amap-bubble {
  position: relative;
  width: 44px;
  height: 44px;
  font-family: var(--font-sans);
}

.amap-bubble__avatar {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--bubble-bg, #eef1ff);
  color: var(--bubble-ink, #2a41b6);
  border: 3px solid #fff;
  box-shadow:
    0 0 0 1px rgba(28, 28, 30, 0.08),
    0 6px 18px -10px rgba(28, 28, 30, 0.38);
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
  transition: transform 0.2s ease;
}

.amap-bubble__status {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  border: 2px solid #fff;
  background: #a5a8b5;
}

.amap-bubble__status.is-online {
  background: #00b473;
}

.amap-bubble__status.is-paused {
  background: #e19a45;
}

.amap-bubble__status.is-locating {
  background: #f59e2c;
}

.amap-bubble__label {
  position: absolute;
  left: 50%;
  top: 27px;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(224, 226, 232, 0.9);
  box-shadow: 0 3px 10px -6px rgba(28, 28, 30, 0.3);
  color: #1c1c1e;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
}

.amap-bubble__time {
  color: #838796;
  font-size: 10px;
  font-weight: 500;
}

.amap-bubble.is-selected .amap-bubble__avatar {
  transform: scale(1.08);
  box-shadow:
    0 0 0 3px #5b76fe,
    0 10px 26px -12px rgba(42, 65, 182, 0.55);
}

.amap-bubble.is-selected .amap-bubble__label {
  background: #1c1c1e;
  border-color: #1c1c1e;
  color: #fff;
}

.amap-bubble.is-selected .amap-bubble__time {
  color: rgba(255, 255, 255, 0.65);
}

.amap-bubble.is-offline .amap-bubble__avatar {
  opacity: 0.58;
  filter: saturate(0.6);
}
</style>
