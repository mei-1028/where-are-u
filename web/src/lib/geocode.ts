import { isAmapConfigured, loadAMap } from './amap'

/**
 * Reverse geocoding via the AMap JS API.
 *
 * The API contract only carries lat/lng, so a friendly address has to be
 * resolved in the browser. Results are cached per ~11m grid cell and in-flight
 * requests are de-duplicated, so several members standing together only cost
 * one lookup. Everything fails soft: callers fall back to a local landmark or a
 * neutral label, never to raw coordinates.
 */

const CACHE_LIMIT = 240
const TIMEOUT_MS = 6000

const cache = new Map<string, string>()
const pending = new Map<string, Promise<string | null>>()

let geocoder: any = null
let geocoderPromise: Promise<any> | null = null

function cacheKey(lat: number, lng: number): string {
  return `${lat.toFixed(4)},${lng.toFixed(4)}`
}

/**
 * Inserts into a Map used as an LRU: deleting before setting moves the key to
 * the end, so the eviction below always drops the least recently *used* entry.
 * A plain FIFO would evict a home address that is read constantly.
 */
function remember(key: string, value: string): void {
  cache.delete(key)
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest !== undefined) cache.delete(oldest)
  }
  cache.set(key, value)
}

/** Cache hit that also refreshes recency for the LRU. */
function recall(key: string): string | undefined {
  const hit = cache.get(key)
  if (hit === undefined) return undefined
  cache.delete(key)
  cache.set(key, hit)
  return hit
}

function getGeocoder(AMap: any): Promise<any> {
  if (geocoder) return Promise.resolve(geocoder)
  if (geocoderPromise) return geocoderPromise
  geocoderPromise = new Promise((resolve, reject) => {
    try {
      AMap.plugin('AMap.Geocoder', () => {
        try {
          geocoder = new AMap.Geocoder({ radius: 300, extensions: 'all' })
          resolve(geocoder)
        } catch (err) {
          geocoderPromise = null
          reject(err)
        }
      })
    } catch (err) {
      geocoderPromise = null
      reject(err)
    }
  })
  return geocoderPromise
}

/**
 * Builds a compact, readable address ("常熟市琴川街道虞枫家园") from the
 * structured components.
 *
 * A residential area / building name is the part people actually recognise, so
 * the AOI name wins over the street number when AMap supplies one. Without it
 * the card would read "达海路6号" for a point that is plainly inside 虞枫家园.
 * The full formattedAddress is only a last resort: it tends to carry a long POI
 * name that does not fit a member card.
 */
function composeAddress(regeocode: any): string {
  const component = regeocode?.addressComponent ?? {}
  const areaName = String(regeocode?.aois?.[0]?.name ?? '').trim()
  const parts = [
    component.district || component.city,
    component.township,
    areaName || component.street,
    areaName ? '' : component.streetNumber,
  ]
    .map((part) => (typeof part === 'string' ? part.trim() : ''))
    .filter(Boolean)
  const compact = Array.from(new Set(parts)).join('')
  if (compact) return compact
  return String(regeocode?.formattedAddress ?? '').trim()
}

export function reverseGeocode(lat: number, lng: number): Promise<string | null> {
  if (!isAmapConfigured || !Number.isFinite(lat) || !Number.isFinite(lng)) {
    return Promise.resolve(null)
  }
  const key = cacheKey(lat, lng)
  const cached = recall(key)
  if (cached) return Promise.resolve(cached)
  const inFlight = pending.get(key)
  if (inFlight) return inFlight

  const task = (async (): Promise<string | null> => {
    try {
      const AMap = await loadAMap()
      const instance = await getGeocoder(AMap)
      const result = await new Promise<any>((resolve, reject) => {
        const timer = window.setTimeout(
          () => reject(new Error('反地理编码超时')),
          TIMEOUT_MS,
        )
        instance.getAddress([lng, lat], (status: string, response: any) => {
          window.clearTimeout(timer)
          if (status === 'complete' && response?.regeocode) resolve(response.regeocode)
          else reject(new Error(`反地理编码失败: ${status}`))
        })
      })
      const label = composeAddress(result)
      if (label) {
        remember(key, label)
        return label
      }
      return null
    } catch {
      return null
    } finally {
      pending.delete(key)
    }
  })()

  pending.set(key, task)
  return task
}
