import type { ApiUser } from '@/api/types'

export const TOKEN_KEY = 'wa_u_token'

export const USER_KEY = 'wa_u_user'
export const FAMILY_KEY = 'wa_u_family'
export const API_BASE_KEY = 'wa_u_api_base'

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function readToken(): string | null {
  return storage()?.getItem(TOKEN_KEY) ?? null
}

export function writeToken(token: string | null): void {
  const s = storage()
  if (!s) return
  if (token) s.setItem(TOKEN_KEY, token)
  else s.removeItem(TOKEN_KEY)
}

export function readCachedUser(): ApiUser | null {
  const raw = storage()?.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<ApiUser>
    if (parsed && typeof parsed.id === 'string' && typeof parsed.nickname === 'string') {
      return {
        id: parsed.id,
        username: parsed.username ?? '',
        nickname: parsed.nickname,
      }
    }
  } catch {
    /* ignore malformed cache */
  }
  return null
}

export function writeCachedUser(user: ApiUser | null): void {
  const s = storage()
  if (!s) return
  if (user) s.setItem(USER_KEY, JSON.stringify(user))
  else s.removeItem(USER_KEY)
}

export function readCachedFamilyId(): string | null {
  return storage()?.getItem(FAMILY_KEY) ?? null
}

export function writeCachedFamilyId(familyId: string | null): void {
  const s = storage()
  if (!s) return
  if (familyId) s.setItem(FAMILY_KEY, familyId)
  else s.removeItem(FAMILY_KEY)
}

export type ApiBaseValidation =
  | { ok: true; value: string }
  | { ok: false; error: string }

export interface ApiBaseValidationOptions {
  /** Tests can pin this instead of relying on the Vite test environment. */
  dev?: boolean
  /** Defaults to window.location.origin in the browser. */
  currentOrigin?: string
}

function stripTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '')
}

function isLocalHttpHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  if (host === 'localhost' || host === '::1' || host.endsWith('.localhost')) return true
  const ipv4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
  if (!ipv4) return false
  const [a, b] = [Number(ipv4[1]), Number(ipv4[2])]
  if ([a, b, Number(ipv4[3]), Number(ipv4[4])].some((part) => part > 255)) return false
  return (
    a === 10 ||
    a === 127 ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168)
  )
}

/**
 * Advanced server override validation. Passwords must never be posted to an
 * arbitrary plaintext host, so HTTP is limited to same-origin URLs and (in
 * development) localhost/private LAN addresses. Everything else must be HTTPS.
 */
export function validateApiBase(
  input: string,
  options: ApiBaseValidationOptions = {},
): ApiBaseValidation {
  const raw = input.trim()
  if (!raw) return { ok: false, error: '请输入服务器地址' }

  const currentOrigin =
    options.currentOrigin ??
    (typeof window !== 'undefined' ? window.location.origin : '')

  if (raw.startsWith('/')) {
    if (raw.startsWith('//')) {
      return { ok: false, error: '服务器地址必须为同源相对路径或完整的 http(s) 地址' }
    }
    try {
      const url = new URL(raw, currentOrigin || 'http://localhost')
      if (url.search || url.hash) {
        return { ok: false, error: '服务器地址不能包含查询参数或锚点' }
      }
      return { ok: true, value: stripTrailingSlash(raw) }
    } catch {
      return { ok: false, error: '服务器地址格式无效' }
    }
  }

  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return { ok: false, error: '服务器地址格式无效，请填写完整的 http(s) 地址' }
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { ok: false, error: '服务器地址只支持 http 或 https' }
  }
  if (url.username || url.password) {
    return { ok: false, error: '服务器地址不能包含账号或密码' }
  }
  if (url.search || url.hash) {
    return { ok: false, error: '服务器地址不能包含查询参数或锚点' }
  }

  const sameOrigin = Boolean(currentOrigin) && url.origin === currentOrigin
  const dev = options.dev ?? import.meta.env.DEV
  if (url.protocol === 'http:' && !sameOrigin) {
    if (!dev) {
      return { ok: false, error: '生产环境只允许 HTTPS 或同源地址' }
    }
    if (!isLocalHttpHost(url.hostname)) {
      return { ok: false, error: 'HTTP 仅允许 localhost 或内网开发地址，请改用 HTTPS' }
    }
  }

  return {
    ok: true,
    value: stripTrailingSlash(`${url.origin}${url.pathname}`),
  }
}

export function getApiBase(): string {
  const s = storage()
  const override = s?.getItem(API_BASE_KEY)?.trim()
  if (override) {
    const validation = validateApiBase(override)
    if (validation.ok) return validation.value
    // Drop a tampered or previously-saved insecure override.
    s?.removeItem(API_BASE_KEY)
  }

  const fromEnv = String(import.meta.env.VITE_API_BASE_URL ?? '').trim()
  if (!fromEnv) return '/api'
  return stripTrailingSlash(fromEnv)
}

export function setApiBaseOverride(value: string): ApiBaseValidation {
  const s = storage()
  const raw = value.trim()
  if (!raw) {
    s?.removeItem(API_BASE_KEY)
    return { ok: true, value: getApiBase() }
  }
  const validation = validateApiBase(raw)
  if (validation.ok) s?.setItem(API_BASE_KEY, validation.value)
  return validation
}

/** Called on explicit logout so the next session starts from the configured base. */
export function clearApiBaseOverride(): void {
  storage()?.removeItem(API_BASE_KEY)
}

export function parseMockFlag(value: string | null | undefined): boolean {
  return String(value ?? '').trim().toLowerCase() === 'true'
}

/** Mock is opt-in. A missing flag (or any value other than "true") is real mode. */
export const IS_MOCK_MODE = parseMockFlag(import.meta.env.VITE_USE_MOCK)
