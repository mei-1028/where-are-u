import { getApiBase, readToken } from '@/lib/session'
import { ApiError } from './errors'
import type {
  AlertListOptions,
  AlertRuleInfo,
  AlertRuleType,
  AlertsResponse,
  ApiSurface,
  AuthResult,
  CommandResponse,
  CreateDeviceInput,
  CreateDeviceResponse,
  CreateFollowInput,
  DevicesResponse,
  FamilyResponse,
  FollowInfo,
  FollowerInfo,
  HistoryResponse,
  InviteResetResponse,
  LatestResponse,
  LocateCommandResponse,
  LoginInput,
  MeResponse,
  RegisterInput,
} from './types'

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  body?: unknown
  auth?: boolean
}

function extractError(status: number, body: unknown): ApiError {
  if (body && typeof body === 'object' && 'error' in body) {
    const error = (body as { error?: { code?: string; message?: string } }).error
    if (error && typeof error.code === 'string') {
      return new ApiError(status, error.code, error.message ?? '请求失败')
    }
  }
  return new ApiError(status, status === 404 ? 'NOT_FOUND' : 'HTTP_ERROR', `请求失败（${status}）`)
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true } = options
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const token = auth ? readToken() : null
  if (token) headers.Authorization = `Bearer ${token}`

  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 15_000)
  let response: Response
  try {
    response = await fetch(`${getApiBase()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new ApiError(0, 'NETWORK', '请求超时，请稍后重试')
    }
    throw new ApiError(0, 'NETWORK', '无法连接服务器，请检查网络或服务器地址')
  } finally {
    window.clearTimeout(timer)
  }

  if (response.status === 401 && token) {
    window.dispatchEvent(new CustomEvent('wa:unauthorized'))
  }

  if (!response.ok) {
    let body: unknown = null
    try {
      body = await response.json()
    } catch {
      /* no JSON error body */
    }
    throw extractError(response.status, body)
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const realApi: ApiSurface = {
  auth: {
    login(input: LoginInput) {
      return request<AuthResult>('/auth/login', { method: 'POST', body: input, auth: false })
    },
    register(input: RegisterInput) {
      return request<AuthResult>('/auth/register', { method: 'POST', body: input, auth: false })
    },
    me() {
      return request<MeResponse>('/me')
    },
  },
  families: {
    create(name: string) {
      return request<{ family: import('./types').FamilyInfo }>('/families', {
        method: 'POST',
        body: { name },
      })
    },
    join(code: string) {
      return request<{ family: import('./types').FamilyInfo }>('/families/join', {
        method: 'POST',
        body: { code },
      })
    },
    me() {
      return request<FamilyResponse>('/families/me')
    },
    resetInvite() {
      return request<InviteResetResponse>('/families/invite/reset', { method: 'POST' })
    },
    removeMember(userId: string) {
      return request<void>(`/families/members/${encodeURIComponent(userId)}`, {
        method: 'DELETE',
      })
    },
    leave() {
      return request<void>('/families/leave', { method: 'POST' })
    },
  },
  devices: {
    list() {
      return request<DevicesResponse>('/devices')
    },
    create(input: CreateDeviceInput) {
      return request<CreateDeviceResponse>('/devices', { method: 'POST', body: input })
    },
    rename(id: string, name: string) {
      return request<{ device: import('./types').DeviceInfo }>(
        `/devices/${encodeURIComponent(id)}`,
        { method: 'PATCH', body: { name } },
      )
    },
    remove(id: string) {
      return request<void>(`/devices/${encodeURIComponent(id)}`, { method: 'DELETE' })
    },
  },
  locations: {
    latest() {
      return request<LatestResponse>('/families/me/latest')
    },
    history(deviceId: string, from: string, to: string, limit = 500) {
      return request<HistoryResponse>(
        `/devices/${encodeURIComponent(deviceId)}/locations${query({ from, to, limit })}`,
      )
    },
  },
  locate: {
    request(deviceId: string) {
      return request<LocateCommandResponse>(
        `/devices/${encodeURIComponent(deviceId)}/locate`,
        { method: 'POST' },
      )
    },
    command(commandId: string) {
      return request<CommandResponse>(`/commands/${encodeURIComponent(commandId)}`)
    },
  },
  follows: {
    list() {
      return request<{ follows: FollowInfo[] }>('/follows')
    },
    create(input: CreateFollowInput) {
      return request<{ follow: FollowInfo }>('/follows', { method: 'POST', body: input })
    },
    remove(id: string) {
      return request<void>(`/follows/${encodeURIComponent(id)}`, { method: 'DELETE' })
    },
    followers() {
      return request<{ followers: FollowerInfo[] }>('/follows/followers')
    },
    createRule(followId: string, type: AlertRuleType, config?: Record<string, unknown>) {
      return request<{ rule: AlertRuleInfo }>(
        `/follows/${encodeURIComponent(followId)}/rules`,
        { method: 'POST', body: { type, config } },
      )
    },
    updateRule(
      followId: string,
      ruleId: string,
      patch: { enabled?: boolean; config?: Record<string, unknown> },
    ) {
      return request<{ rule: AlertRuleInfo }>(
        `/follows/${encodeURIComponent(followId)}/rules/${encodeURIComponent(ruleId)}`,
        { method: 'PATCH', body: patch },
      )
    },
    deleteRule(followId: string, ruleId: string) {
      return request<void>(
        `/follows/${encodeURIComponent(followId)}/rules/${encodeURIComponent(ruleId)}`,
        { method: 'DELETE' },
      )
    },
  },
  alerts: {
    list(options: AlertListOptions = {}) {
      return request<AlertsResponse>(
        `/alerts${query({
          limit: options.limit,
          unread: options.unreadOnly ? 1 : undefined,
          after: options.after,
          before: options.before,
        })}`,
      )
    },
    markRead(id: string) {
      return request<void>(`/alerts/${encodeURIComponent(id)}/read`, { method: 'POST' })
    },
    markAllRead() {
      return request<{ updated: number }>('/alerts/read-all', { method: 'POST' })
    },
  },
}
