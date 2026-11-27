import type { LocationPoint } from '@/api/types'

/** ViewBox used by the hand-drawn schematic map (maps 1:1 to the demo area). */
export const MAP_VIEWBOX = { width: 1200, height: 820 }

/** Demo area around 文三路 / 武林广场 / 黄龙 in Hangzhou. */
export const MAP_BOUNDS = {
  south: 30.254,
  north: 30.299,
  west: 120.112,
  east: 120.19,
}

export interface ViewBoxPoint {
  x: number
  y: number
}

export interface MapFit {
  scaleX: number
  scaleY: number
  offsetX: number
  offsetY: number
  stretch: boolean
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** Project WGS-84-ish coordinates into the schematic map's viewBox. */
export function projectToViewBox(lat: number, lng: number): ViewBoxPoint {
  const x = ((lng - MAP_BOUNDS.west) / (MAP_BOUNDS.east - MAP_BOUNDS.west)) * MAP_VIEWBOX.width
  const y = ((MAP_BOUNDS.north - lat) / (MAP_BOUNDS.north - MAP_BOUNDS.south)) * MAP_VIEWBOX.height
  return { x, y }
}

/**
 * Fit the fixed artboard into an arbitrary viewport.
 * Landscape viewports are letterboxed (never cropped, so every family marker
 * stays visible); narrow portrait viewports stretch the illustration so the
 * map still fills the available canvas on phones.
 */
export function fitMapViewBox(width: number, height: number): MapFit {
  if (!width || !height) {
    return { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: 0, stretch: false }
  }
  const ratio = width / height
  if (ratio < 1.12) {
    return {
      scaleX: width / MAP_VIEWBOX.width,
      scaleY: height / MAP_VIEWBOX.height,
      offsetX: 0,
      offsetY: 0,
      stretch: true,
    }
  }
  const scale = Math.min(width / MAP_VIEWBOX.width, height / MAP_VIEWBOX.height)
  return {
    scaleX: scale,
    scaleY: scale,
    offsetX: (width - MAP_VIEWBOX.width * scale) / 2,
    offsetY: (height - MAP_VIEWBOX.height * scale) / 2,
    stretch: false,
  }
}

export function projectToScreen(
  lat: number,
  lng: number,
  fit: MapFit,
): { x: number; y: number } {
  const point = projectToViewBox(lat, lng)
  return {
    x: fit.offsetX + point.x * fit.scaleX,
    y: fit.offsetY + point.y * fit.scaleY,
  }
}

export interface Landmark {
  name: string
  lat: number
  lng: number
}

/** A tiny local gazetteer so the UI can show a human address without a geocoder. */
export const LANDMARKS: Landmark[] = [
  { name: '文三路 90 号', lat: 30.2789, lng: 120.1294 },
  { name: '武林广场', lat: 30.273, lng: 120.1657 },
  { name: '黄龙体育中心', lat: 30.2666, lng: 120.1338 },
  { name: '翠苑一区', lat: 30.2862, lng: 120.1256 },
  { name: '学院路地铁站', lat: 30.2782, lng: 120.1418 },
  { name: '古荡新村', lat: 30.2838, lng: 120.1322 },
  { name: '黄龙洞', lat: 30.2663, lng: 120.1395 },
  { name: '浙江图书馆', lat: 30.263, lng: 120.135 },
  { name: '武林门', lat: 30.274, lng: 120.1595 },
  { name: '沈塘桥', lat: 30.28, lng: 120.155 },
  { name: '朝晖三区', lat: 30.286, lng: 120.158 },
  { name: '西湖文化广场', lat: 30.281, lng: 120.1687 },
  { name: '教工路', lat: 30.2802, lng: 120.1452 },
  { name: '天目山路', lat: 30.2705, lng: 120.1268 },
  { name: '浙大玉泉校区', lat: 30.2637, lng: 120.1277 },
  { name: '大关路', lat: 30.293, lng: 120.146 },
  { name: '松木场', lat: 30.269, lng: 120.142 },
  { name: '西溪湿地', lat: 30.265, lng: 120.116 },
]

const EARTH_RADIUS_M = 6371008.8

export function distanceMeters(
  a: { lat: number; lng: number },
  b: { lat: number; lng: number },
): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180
  const dLat = toRad(b.lat - a.lat)
  const dLng = toRad(b.lng - a.lng)
  const lat1 = toRad(a.lat)
  const lat2 = toRad(b.lat)
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h))
}

export function nearestLandmark(lat: number, lng: number): Landmark | null {
  let best: Landmark | null = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const landmark of LANDMARKS) {
    const d = distanceMeters({ lat, lng }, landmark)
    if (d < bestDistance) {
      bestDistance = d
      best = landmark
    }
  }
  return best
}

/**
 * Best-effort local place name from the bundled landmark list. Returns an empty
 * string when the point is far from any known landmark; raw coordinates are
 * never surfaced to users. Callers pair this with remote reverse geocoding
 * (see `usePlaceLabel`).
 */
export function describeLocation(lat: number, lng: number): string {
  const nearest = nearestLandmark(lat, lng)
  if (nearest) {
    const d = distanceMeters({ lat, lng }, nearest)
    if (d < 900) return nearest.name
    if (d < 3200) return `${nearest.name}附近`
  }
  return ''
}

export function locationAgeMs(point: LocationPoint | null | undefined): number | null {
  if (!point?.recordedAt) return null
  const time = new Date(point.recordedAt).getTime()
  if (Number.isNaN(time)) return null
  return Math.max(0, Date.now() - time)
}

export function isStaleLocation(
  point: LocationPoint | null | undefined,
  thresholdMs = 30 * 60 * 1000,
): boolean {
  const age = locationAgeMs(point)
  return age !== null && age > thresholdMs
}

export function trailDistanceMeters(points: LocationPoint[]): number {
  let total = 0
  for (let i = 1; i < points.length; i += 1) {
    total += distanceMeters(points[i - 1]!, points[i]!)
  }
  return total
}

/** Sample a polyline by normalized progress (0..1). */
export function interpolateTrail(
  points: LocationPoint[],
  progress: number,
): { point: LocationPoint | null; index: number } {
  if (!points.length) return { point: null, index: 0 }
  if (points.length === 1) return { point: points[0]!, index: 0 }
  const p = clamp(progress, 0, 1)
  const position = p * (points.length - 1)
  const index = Math.min(points.length - 1, Math.floor(position))
  const next = Math.min(points.length - 1, index + 1)
  const t = position - index
  const a = points[index]!
  const b = points[next]!
  if (t === 0 || index === next) return { point: a, index }
  return {
    point: {
      ...a,
      lat: a.lat + (b.lat - a.lat) * t,
      lng: a.lng + (b.lng - a.lng) * t,
      recordedAt: a.recordedAt,
    },
    index,
  }
}
