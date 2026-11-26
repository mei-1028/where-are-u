<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { LocationPoint } from '@/api/types'
import { isAmapConfigured, loadAMap } from '@/lib/amap'
import {
  MAP_VIEWBOX,
  clamp,
  interpolateTrail,
  projectToViewBox,
} from '@/lib/geo'
import Icon from './Icon.vue'
import SchematicBase from './SchematicBase.vue'

const props = defineProps<{
  points: LocationPoint[]
  progress: number
}>()

type MapMode = 'schematic' | 'loading' | 'amap'

const mode = ref<MapMode>(isAmapConfigured ? 'loading' : 'schematic')
const amapError = ref('')
const viewport = ref<HTMLElement | null>(null)
const amapEl = ref<HTMLElement | null>(null)
const size = ref({ w: 0, h: 0 })

let resizeObserver: ResizeObserver | null = null
let map: any = null
let amapRouteOverlays: any[] = []
let amapCurrentMarker: any = null

interface MapWindow {
  x: number
  y: number
  w: number
  h: number
}

/**
 * Zoom the schematic to the route instead of showing the whole city. The
 * window is shaped to the viewport when possible; if the day's route is too
 * large for that shape it falls back to a rectangular fit (preserveAspectRatio
 * keeps the art and markers aligned either way).
 */
const mapWindow = computed<MapWindow>(() => {
  if (!props.points.length || !size.value.w || !size.value.h) {
    return { x: 0, y: 0, w: MAP_VIEWBOX.width, h: MAP_VIEWBOX.height }
  }
  const projected = props.points.map((point) => projectToViewBox(point.lat, point.lng))
  const xs = projected.map((point) => point.x)
  const ys = projected.map((point) => point.y)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  const spanX = Math.max(80, maxX - minX)
  const spanY = Math.max(80, maxY - minY)

  if (spanX + 180 > MAP_VIEWBOX.width || spanY + 180 > MAP_VIEWBOX.height) {
    const w = Math.min(MAP_VIEWBOX.width, spanX + 160)
    const h = Math.min(MAP_VIEWBOX.height, spanY + 160)
    return {
      x: clamp(cx - w / 2, 0, MAP_VIEWBOX.width - w),
      y: clamp(cy - h / 2, 0, MAP_VIEWBOX.height - h),
      w,
      h,
    }
  }

  const aspect = clamp(size.value.w / size.value.h, 0.7, 3.4)
  let w = Math.max(spanX * 1.7 + 180, 500)
  let h = Math.max(spanY * 1.7 + 180, 340)
  if (w / h > aspect) h = w / aspect
  else w = h * aspect

  if (w > MAP_VIEWBOX.width) {
    w = MAP_VIEWBOX.width
    h = w / aspect
  }
  if (h > MAP_VIEWBOX.height) {
    h = MAP_VIEWBOX.height
    w = h * aspect
  }

  // Guard against an aspect-driven crop that would hide part of the route.
  if (spanX + 100 > w || spanY + 100 > h) {
    const fw = Math.min(MAP_VIEWBOX.width, spanX + 160)
    const fh = Math.min(MAP_VIEWBOX.height, spanY + 160)
    return {
      x: clamp(cx - fw / 2, 0, MAP_VIEWBOX.width - fw),
      y: clamp(cy - fh / 2, 0, MAP_VIEWBOX.height - fh),
      w: fw,
      h: fh,
    }
  }

  return {
    x: clamp(cx - w / 2, 0, MAP_VIEWBOX.width - w),
    y: clamp(cy - h / 2, 0, MAP_VIEWBOX.height - h),
    w,
    h,
  }
})

const viewBoxAttr = computed(
  () => `${mapWindow.value.x} ${mapWindow.value.y} ${mapWindow.value.w} ${mapWindow.value.h}`,
)

const hideLabels = computed(() => mapWindow.value.w < 960)

function projectToScreen(lat: number, lng: number): { x: number; y: number } {
  const projected = projectToViewBox(lat, lng)
  const box = mapWindow.value
  const scale = Math.min(size.value.w / box.w, size.value.h / box.h)
  return {
    x: (size.value.w - box.w * scale) / 2 + (projected.x - box.x) * scale,
    y: (size.value.h - box.h * scale) / 2 + (projected.y - box.y) * scale,
  }
}

const badgeLabel = computed(() => {
  if (mode.value === 'amap') return '高德地图 · 2.0'
  if (mode.value === 'loading') return '正在加载高德地图…'
  if (amapError.value) return '示意地图 · 高德未加载'
  return '示意地图 · 离线可用'
})

const currentSample = computed(() => interpolateTrail(props.points, props.progress))
const currentPoint = computed(() => currentSample.value.point)

const trailViewPoints = computed(() =>
  props.points
    .map((point) => {
      const projected = projectToViewBox(point.lat, point.lng)
      return `${projected.x.toFixed(1)},${projected.y.toFixed(1)}`
    })
    .join(' '),
)

const playedViewPoints = computed(() => {
  if (!props.points.length) return ''
  const index = currentSample.value.index
  const slice = props.points.slice(0, index + 1)
  const current = currentPoint.value
  if (current) {
    const last = slice[slice.length - 1]
    if (!last || last.lat !== current.lat || last.lng !== current.lng) slice.push(current)
  }
  return slice
    .map((point) => {
      const projected = projectToViewBox(point.lat, point.lng)
      return `${projected.x.toFixed(1)},${projected.y.toFixed(1)}`
    })
    .join(' ')
})

const screenMarkers = computed(() => {
  const start = props.points[0]
  const end = props.points[props.points.length - 1]
  return {
    start: start ? projectToScreen(start.lat, start.lng) : null,
    end: end ? projectToScreen(end.lat, end.lng) : null,
    current: currentPoint.value
      ? projectToScreen(currentPoint.value.lat, currentPoint.value.lng)
      : null,
  }
})

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

function flagHtml(label: string, kind: 'start' | 'end'): string {
  return `<span class="trail-amap-flag trail-amap-flag--${kind}">${escapeHtml(label)}</span>`
}

function currentHtml(): string {
  return '<span class="trail-amap-current"><span class="trail-amap-current__dot"></span></span>'
}

function syncAmapRoute(shouldFit: boolean): void {
  if (!map || mode.value !== 'amap' || !window.AMap) return
  const AMap = window.AMap
  if (amapRouteOverlays.length) {
    map.remove(amapRouteOverlays)
    amapRouteOverlays = []
    amapCurrentMarker = null
  }
  if (!props.points.length) return

  const path = props.points.map((point) => [point.lng, point.lat])
  const overlays: any[] = []
  const markers: any[] = []

  if (path.length > 1) {
    overlays.push(
      new AMap.Polyline({
        path,
        strokeColor: '#FFFFFF',
        strokeWeight: 10,
        strokeOpacity: 0.95,
        lineJoin: 'round',
        zIndex: 40,
      }),
      new AMap.Polyline({
        path,
        strokeColor: '#5B76FE',
        strokeWeight: 5,
        strokeOpacity: 0.95,
        lineJoin: 'round',
        showDir: false,
        zIndex: 41,
      }),
    )
  }

  const start = props.points[0]!
  const end = props.points[props.points.length - 1]!
  const startMarker = new AMap.Marker({
    position: [start.lng, start.lat],
    content: flagHtml('起', 'start'),
    anchor: 'center',
    zIndex: 120,
  })
  overlays.push(startMarker)
  markers.push(startMarker)
  if (props.points.length > 1) {
    const endMarker = new AMap.Marker({
      position: [end.lng, end.lat],
      content: flagHtml('终', 'end'),
      anchor: 'center',
      zIndex: 120,
    })
    overlays.push(endMarker)
    markers.push(endMarker)
  }

  const current = currentPoint.value
  if (current) {
    amapCurrentMarker = new AMap.Marker({
      position: [current.lng, current.lat],
      content: currentHtml(),
      anchor: 'center',
      zIndex: 140,
    })
    if (props.progress <= 0.001 || props.progress >= 0.999) amapCurrentMarker.hide()
    overlays.push(amapCurrentMarker)
  }

  map.add(overlays)
  amapRouteOverlays = overlays

  if (shouldFit) {
    if (props.points.length > 1) {
      map.setFitView(overlays, false, [70, 70, 120, 70])
    } else {
      map.setZoomAndCenter(15, [start.lng, start.lat])
    }
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
      mapStyle: 'amap://styles/whitesmoke',
      showLabel: true,
      resizeEnable: true,
    })
    mode.value = 'amap'
    await nextTick()
    syncAmapRoute(true)
    map.resize?.()
  } catch (err) {
    amapError.value = err instanceof Error ? err.message : '高德地图加载失败'
    mode.value = 'schematic'
  }
}

watch(
  () => props.points,
  () => {
    if (mode.value === 'amap') syncAmapRoute(true)
  },
  { deep: true },
)

watch(
  () => props.progress,
  () => {
    if (mode.value !== 'amap' || !amapCurrentMarker) return
    const current = currentPoint.value
    if (current) amapCurrentMarker.setPosition([current.lng, current.lat])
    if (props.progress <= 0.001 || props.progress >= 0.999) amapCurrentMarker.hide()
    else amapCurrentMarker.show()
  },
)

watch(size, () => {
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
  if (map) {
    map.destroy?.()
    map = null
  }
})
</script>

<template>
  <div ref="viewport" class="history-map" role="region" aria-label="历史轨迹地图">
    <div ref="amapEl" class="history-amap" :class="{ 'is-visible': mode === 'amap' }" />

    <div v-show="mode !== 'amap'" class="history-schematic">
      <SchematicBase
        preserve="meet"
        :hide-labels="hideLabels"
        :view-box="viewBoxAttr"
      />
      <svg
        class="trail-svg"
        :viewBox="viewBoxAttr"
        preserveAspectRatio="xMidYMid meet"
        aria-hidden="true"
      >
        <polyline
          v-if="trailViewPoints"
          :points="trailViewPoints"
          fill="none"
          stroke="#B9C5FF"
          stroke-width="4"
          stroke-linecap="round"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
        <polyline
          v-if="playedViewPoints"
          :points="playedViewPoints"
          fill="none"
          stroke="#FFFFFF"
          stroke-width="9"
          stroke-linecap="round"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
        <polyline
          v-if="playedViewPoints"
          :points="playedViewPoints"
          fill="none"
          stroke="#5B76FE"
          stroke-width="4.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
      </svg>

      <div class="trail-markers" aria-hidden="true">
        <span
          v-if="points.length && screenMarkers.start"
          class="trail-flag trail-flag--start"
          :style="{ left: `${screenMarkers.start.x}px`, top: `${screenMarkers.start.y}px` }"
        >
          起
        </span>
        <span
          v-if="points.length > 1 && screenMarkers.end"
          class="trail-flag trail-flag--end"
          :style="{ left: `${screenMarkers.end.x}px`, top: `${screenMarkers.end.y}px` }"
        >
          终
        </span>
        <span
          v-if="screenMarkers.current && progress > 0.001 && progress < 0.999"
          class="trail-current"
          :style="{ left: `${screenMarkers.current.x}px`, top: `${screenMarkers.current.y}px` }"
        >
          <span class="trail-current__pulse" />
          <span class="trail-current__dot" />
        </span>
      </div>
    </div>

    <div class="history-badge">
      <span v-if="mode === 'loading'" class="spinner" />
      <Icon v-else name="map" :size="14" />
      {{ badgeLabel }}
    </div>

    <div class="trail-legend" aria-hidden="true">
      <span class="trail-legend__item"><i class="trail-legend__line" />轨迹</span>
      <span class="trail-legend__item"><i class="trail-legend__dot trail-legend__dot--start" />起点</span>
      <span class="trail-legend__item"><i class="trail-legend__dot trail-legend__dot--end" />终点</span>
    </div>
  </div>
</template>

<style scoped>
.history-map {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: var(--map-base);
  background-image: radial-gradient(circle at 1px 1px, #e5e7ec 1.1px, transparent 1.2px);
  background-size: 26px 26px;
}

.history-amap {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.35s ease;
}

.history-amap.is-visible {
  opacity: 1;
  pointer-events: auto;
}

.history-schematic {
  position: absolute;
  inset: 0;
}

.history-schematic :deep(svg) {
  width: 100%;
  height: 100%;
}

.trail-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.trail-markers {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.trail-flag {
  position: absolute;
  transform: translate(-50%, -50%);
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 4px 12px -6px rgba(28, 28, 30, 0.4);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  z-index: 2;
}

.trail-flag--start {
  background: var(--teal);
  color: var(--teal-ink);
}

.trail-flag--end {
  background: var(--coral);
  color: var(--coral-ink);
}

.trail-current {
  position: absolute;
  width: 0;
  height: 0;
  z-index: 3;
}

.trail-current__dot {
  position: absolute;
  left: 0;
  top: 0;
  width: 14px;
  height: 14px;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  background: var(--blue);
  border: 2.5px solid #fff;
  box-shadow: 0 0 0 1px rgba(42, 65, 182, 0.25);
}

.trail-current__pulse {
  position: absolute;
  left: 0;
  top: 0;
  width: 14px;
  height: 14px;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  border: 2px solid var(--blue);
  animation: trail-pulse 1.7s var(--ease-out) infinite;
}

@keyframes trail-pulse {
  0% {
    transform: translate(-50%, -50%) scale(0.9);
    opacity: 0.7;
  }
  100% {
    transform: translate(-50%, -50%) scale(2.4);
    opacity: 0;
  }
}

.history-badge {
  position: absolute;
  top: 14px;
  right: 14px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 11px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--ring);
  color: var(--slate);
  font-size: 11px;
  font-weight: 500;
  pointer-events: none;
  backdrop-filter: blur(6px);
}

.history-badge .spinner {
  width: 12px;
  height: 12px;
  border-width: 1.5px;
}

.trail-legend {
  position: absolute;
  left: 14px;
  bottom: 14px;
  display: inline-flex;
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

.trail-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.trail-legend__line {
  display: inline-block;
  width: 18px;
  height: 3px;
  border-radius: 2px;
  background: var(--blue);
}

.trail-legend__dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid #fff;
}

.trail-legend__dot--start {
  background: #66d4c9;
}

.trail-legend__dot--end {
  background: #ee8d8d;
}

@media (max-width: 600px) {
  .history-badge {
    top: 10px;
    right: 10px;
  }

  .trail-legend {
    left: 10px;
    bottom: 10px;
    gap: 9px;
    padding: 0 10px;
  }
}
</style>

<style>
.trail-amap-flag {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 4px 12px -6px rgba(28, 28, 30, 0.4);
  font-family: var(--font-sans);
  font-size: 9.5px;
  font-weight: 600;
}

.trail-amap-flag--start {
  background: #c3faf5;
  color: #0f5f5c;
}

.trail-amap-flag--end {
  background: #ffc6c6;
  color: #7a1f1f;
}

.trail-amap-current {
  position: relative;
  display: block;
  width: 14px;
  height: 14px;
}

.trail-amap-current__dot {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: #5b76fe;
  border: 2.5px solid #fff;
  box-shadow: 0 0 0 1px rgba(42, 65, 182, 0.25);
}
</style>
