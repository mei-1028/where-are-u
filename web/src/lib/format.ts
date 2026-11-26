const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

export function relativeTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '暂无更新'
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) return '暂无更新'
  const diff = Math.max(0, now - time)
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  const days = Math.floor(diff / DAY)
  if (days === 1) return `昨天 ${formatClock(iso)}`
  if (days < 7) return `${days} 天前`
  const date = new Date(iso)
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

export function formatClock(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${formatClock(iso)}`
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '—'
  const totalMinutes = Math.round(ms / MINUTE)
  if (totalMinutes < 60) return `${totalMinutes} 分钟`
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return minutes ? `${hours} 小时 ${minutes} 分` : `${hours} 小时`
}

export function formatKm(meters: number): string {
  if (!Number.isFinite(meters) || meters <= 0) return '0 m'
  if (meters < 1000) return `${Math.round(meters)} m`
  return `${(meters / 1000).toFixed(1)} km`
}

export function formatSpeed(metersPerSecond: number | null | undefined): string {
  if (metersPerSecond == null || !Number.isFinite(metersPerSecond)) return '—'
  return `${(metersPerSecond * 3.6).toFixed(1)} km/h`
}

export function formatAccuracy(accuracy: number | null | undefined): string {
  if (accuracy == null || !Number.isFinite(accuracy)) return '—'
  return `±${Math.round(accuracy)} m`
}

export function formatBattery(battery: number | null | undefined): string {
  if (battery == null || !Number.isFinite(battery)) return '—'
  return `${Math.round(battery)}%`
}

export function initials(name: string): string {
  const trimmed = name.trim()
  if (!trimmed) return '·'
  const first = Array.from(trimmed)[0] ?? '·'
  return /[a-z]/.test(first) ? first.toUpperCase() : first
}

const AVATAR_TONES = [
  { bg: '#EEF1FF', ink: '#2A41B6' },
  { bg: '#C3FAF5', ink: '#0F5F5C' },
  { bg: '#FFE6CD', ink: '#7A4311' },
  { bg: '#FFD8F4', ink: '#7A235F' },
  { bg: '#FFC6C6', ink: '#7A1F1F' },
  { bg: '#FDE0F0', ink: '#74234E' },
]

export function avatarTone(id: string | null | undefined): { bg: string; ink: string } {
  const key = id ?? ''
  let hash = 0
  for (let i = 0; i < key.length; i += 1) hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]!
}

export function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function localDateValue(date: Date = new Date()): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function todayValue(): string {
  return localDateValue()
}

export function addDays(dateValue: string, delta: number): string {
  const date = parseDateValue(dateValue)
  date.setDate(date.getDate() + delta)
  return localDateValue(date)
}

export function parseDateValue(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year ?? 1970, (month ?? 1) - 1, day ?? 1, 0, 0, 0, 0)
}

export function rangeForDate(value: string): { from: string; to: string } {
  const start = parseDateValue(value)
  const end = new Date(start.getFullYear(), start.getMonth(), start.getDate(), 23, 59, 59, 999)
  const now = new Date()
  const cappedEnd = end.getTime() > now.getTime() ? now : end
  return { from: start.toISOString(), to: cappedEnd.toISOString() }
}

export function dateLabel(value: string): string {
  const today = todayValue()
  if (value === today) return '今天'
  if (value === addDays(today, -1)) return '昨天'
  const date = parseDateValue(value)
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

export function fullDateLabel(value: string): string {
  const date = parseDateValue(value)
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日 · ${
    weekdays[date.getDay()]
  }`
}

export function expiresLabel(iso: string | null | undefined): string {
  if (!iso) return ''
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) return ''
  return `${formatDateTime(iso)} 过期`
}

export function formatCoordinate(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`
}

export function formatCount(count: number, unit: string): string {
  return `${count} ${unit}`
}
