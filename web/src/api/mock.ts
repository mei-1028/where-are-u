import { clamp } from '@/lib/geo'
import { localDateValue, parseDateValue } from '@/lib/format'
import { readToken } from '@/lib/session'
import { ApiError } from './errors'
import type {
  AlertInfo,
  AlertListOptions,
  AlertRuleConfig,
  AlertRuleInfo,
  AlertRuleType,
  AlertsResponse,
  ApiSurface,
  ApiUser,
  AuthResult,
  CommandResponse,
  CreateDeviceInput,
  CreateDeviceResponse,
  CreateFollowInput,
  DeviceInfo,
  DevicesResponse,
  FamilyInfo,
  FamilyMemberInfo,
  FamilyResponse,
  FollowInfo,
  FollowerInfo,
  HistoryResponse,
  InviteResetResponse,
  LatestMemberInfo,
  LatestResponse,
  LocateCommandResponse,
  LocateStatus,
  LocationPoint,
  LoginInput,
  MeResponse,
  MemberView,
  RegisterInput,
  Role,
} from './types'

/* -------------------------------------------------------------------------- */
/*  Seeded demo database                                                       */
/* -------------------------------------------------------------------------- */

interface MockUser extends ApiUser {
  password: string
}

interface MockMember {
  userId: string
  role: Role
  sharingPaused: boolean
  joinedAt: string
}

interface MockFamily {
  id: string
  name: string
  inviteCode: string
  inviteExpiresAt: string
  createdAt: string
  clearedAt: string | null
  members: MockMember[]
}

interface MockDevice {
  id: string
  userId: string
  familyId: string
  name: string
  platform: string
  model: string
  osVersion: string
  appVersion: string
  online: boolean
  lastSeenAt: string | null
  lastLocation: LocationPoint | null
  anchor: { lat: number; lng: number }
  battery: number
  accuracy: number
  speed: number
}

interface MockCommand {
  id: string
  deviceId: string
  familyId: string
  requestedBy: string
  status: LocateStatus
  createdAt: string
  deadlineAt: string
  completeAfter: string
  completedAt?: string
}

interface MockRule {
  id: string
  type: AlertRuleType
  enabled: boolean
  config: Record<string, unknown>
  lastFiredAt: string | null
}

interface MockFollow {
  id: string
  familyId: string
  followerId: string
  targetId: string
  createdAt: string
  rules: MockRule[]
}

type MockAlert = AlertInfo & { familyId: string; userId: string }

interface MockDb {
  version: number
  users: MockUser[]
  families: MockFamily[]
  devices: MockDevice[]
  commands: MockCommand[]
  follows: MockFollow[]
  alerts: MockAlert[]
  tokens: Record<string, string>
}

const DB_KEY = 'where-are-u:mock:v1'
const DB_VERSION = 2
const DEMO_PASSWORD = 'demo1234'

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export const mockDemoInfo = {
  username: 'chen',
  password: DEMO_PASSWORD,
  familyName: '陈家',
  inviteCode: '824137',
  memberCount: 4,
}

const seedNow = Date.now()
const isoAgo = (ms: number) => new Date(seedNow - ms).toISOString()
const isoIn = (ms: number) => new Date(seedNow + ms).toISOString()

function seedPoint(
  lat: number,
  lng: number,
  options: { accuracy: number; battery: number; speed: number; ageMs: number },
): LocationPoint {
  return {
    lat,
    lng,
    accuracy: options.accuracy,
    battery: options.battery,
    speed: options.speed,
    bearing: 90,
    provider: 'amap',
    recordedAt: isoAgo(options.ageMs),
  }
}

function seedDb(): MockDb {
  const family: MockFamily = {
    id: 'f_chen',
    name: '陈家',
    inviteCode: '824137',
    inviteExpiresAt: isoIn(24 * 60 * 60 * 1000),
    createdAt: isoAgo(120 * 24 * 60 * 60 * 1000),
    clearedAt: null,
    members: [
      { userId: 'u_1', role: 'admin', sharingPaused: false, joinedAt: isoAgo(120 * 86400000) },
      { userId: 'u_2', role: 'member', sharingPaused: false, joinedAt: isoAgo(96 * 86400000) },
      { userId: 'u_3', role: 'member', sharingPaused: false, joinedAt: isoAgo(88 * 86400000) },
      { userId: 'u_4', role: 'member', sharingPaused: false, joinedAt: isoAgo(64 * 86400000) },
    ],
  }

  const users: MockUser[] = [
    { id: 'u_1', username: 'chen', nickname: '阿哲', password: DEMO_PASSWORD },
    { id: 'u_2', username: 'mama', nickname: '妈妈', password: DEMO_PASSWORD },
    { id: 'u_3', username: 'baba', nickname: '爸爸', password: DEMO_PASSWORD },
    { id: 'u_4', username: 'nainai', nickname: '奶奶', password: DEMO_PASSWORD },
  ]

  const devices: MockDevice[] = [
    {
      id: 'd_1',
      userId: 'u_1',
      familyId: family.id,
      name: '阿哲的 Pixel 8',
      platform: 'android',
      model: 'Pixel 8',
      osVersion: 'Android 15',
      appVersion: '0.1.0',
      online: true,
      lastSeenAt: isoAgo(36_000),
      lastLocation: seedPoint(30.2789, 120.1295, {
        accuracy: 12,
        battery: 82,
        speed: 0,
        ageMs: 36_000,
      }),
      anchor: { lat: 30.2789, lng: 120.1295 },
      battery: 82,
      accuracy: 12,
      speed: 0,
    },
    {
      id: 'd_2',
      userId: 'u_2',
      familyId: family.id,
      name: '妈妈的小米 14',
      platform: 'android',
      model: 'Xiaomi 14',
      osVersion: 'Android 15',
      appVersion: '0.1.0',
      online: true,
      lastSeenAt: isoAgo(92_000),
      lastLocation: seedPoint(30.273, 120.1657, {
        accuracy: 18,
        battery: 64,
        speed: 4.2,
        ageMs: 92_000,
      }),
      anchor: { lat: 30.273, lng: 120.1657 },
      battery: 64,
      accuracy: 18,
      speed: 4.2,
    },
    {
      id: 'd_3',
      userId: 'u_3',
      familyId: family.id,
      name: '爸爸的 Mate 60',
      platform: 'android',
      model: 'HUAWEI Mate 60',
      osVersion: 'HarmonyOS 4.2',
      appVersion: '0.1.0',
      online: true,
      lastSeenAt: isoAgo(184_000),
      lastLocation: seedPoint(30.2666, 120.1338, {
        accuracy: 24,
        battery: 41,
        speed: 0.6,
        ageMs: 184_000,
      }),
      anchor: { lat: 30.2666, lng: 120.1338 },
      battery: 41,
      accuracy: 24,
      speed: 0.6,
    },
    {
      id: 'd_4',
      userId: 'u_4',
      familyId: family.id,
      name: '奶奶的 Redmi Note 12',
      platform: 'android',
      model: 'Redmi Note 12',
      osVersion: 'Android 14',
      appVersion: '0.1.0',
      online: false,
      lastSeenAt: isoAgo(2 * 60 * 60 * 1000 + 12 * 60 * 1000),
      lastLocation: seedPoint(30.2862, 120.1256, {
        accuracy: 65,
        battery: 18,
        speed: 0,
        ageMs: 2 * 60 * 60 * 1000 + 12 * 60 * 1000,
      }),
      anchor: { lat: 30.2862, lng: 120.1256 },
      battery: 18,
      accuracy: 65,
      speed: 0,
    },
  ]

  const follows: MockFollow[] = [
    {
      id: 'f_1',
      familyId: family.id,
      followerId: 'u_1',
      targetId: 'u_4',
      createdAt: isoAgo(3 * 86_400_000),
      rules: [
        {
          id: 'r_1',
          type: 'MOVEMENT',
          enabled: true,
          config: { thresholdMeters: 500, cooldownMinutes: 30 },
          lastFiredAt: null,
        },
        {
          id: 'r_2',
          type: 'LOW_BATTERY',
          enabled: true,
          config: { percent: 20 },
          lastFiredAt: isoAgo(2 * 3_600_000),
        },
        {
          id: 'r_3',
          type: 'STALE',
          enabled: true,
          config: { hours: 12 },
          lastFiredAt: isoAgo(2 * 3_600_000),
        },
      ],
    },
  ]

  const alerts: MockAlert[] = [
    {
      id: 'a_1',
      userId: 'u_1',
      familyId: family.id,
      type: 'LOW_BATTERY',
      target: { userId: 'u_4', nickname: '奶奶' },
      title: '奶奶 手机电量低',
      body: '剩余 18%，可能很快关机',
      silent: false,
      data: { lat: 30.2862, lng: 120.1256, battery: 18, recordedAt: isoAgo(2 * 3_600_000) },
      occurredAt: isoAgo(2 * 3_600_000),
      createdAt: isoAgo(2 * 3_600_000),
      readAt: null,
    },
    {
      id: 'a_2',
      userId: 'u_1',
      familyId: family.id,
      type: 'STALE',
      target: { userId: 'u_4', nickname: '奶奶' },
      title: '奶奶 已 12 小时没有更新位置',
      body: '可能关机、断网，或 App 被系统清理',
      silent: false,
      data: {
        lat: 30.2862,
        lng: 120.1256,
        lastRecordedAt: isoAgo(14 * 3_600_000),
        hours: 12,
      },
      occurredAt: isoAgo(2 * 3_600_000),
      createdAt: isoAgo(2 * 3_600_000),
      readAt: isoAgo(3_600_000),
    },
  ]

  return {
    version: DB_VERSION,
    users,
    families: [family],
    devices,
    commands: [],
    follows,
    alerts,
    tokens: {},
  }
}

let db: MockDb | null = null

function getDb(): MockDb {
  if (db) return db
  const raw = storage()?.getItem(DB_KEY)
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as MockDb
      if (parsed && parsed.version === DB_VERSION && Array.isArray(parsed.users)) {
        db = parsed
        return db
      }
    } catch {
      /* corrupt mock db — reseed */
    }
  }
  db = seedDb()
  persist()
  return db
}

function persist(): void {
  if (!db) return
  try {
    storage()?.setItem(DB_KEY, JSON.stringify(db))
  } catch {
    /* storage full / disabled — keep working in memory */
  }
}

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                    */
/* -------------------------------------------------------------------------- */

const sleep = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms))

function newId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 9)}`
}

function randomDigits(length: number): string {
  let out = ''
  for (let i = 0; i < length; i += 1) out += Math.floor(Math.random() * 10)
  return out
}

function generateInviteCode(): string {
  const current = getDb()
  let code = ''
  do {
    code = randomDigits(6)
  } while (current.families.some((family) => family.inviteCode === code))
  return code
}

function findUser(userId: string): MockUser {
  const user = getDb().users.find((item) => item.id === userId)
  if (!user) throw new ApiError(404, 'NOT_FOUND', '用户不存在')
  return user
}

function publicUser(user: MockUser): ApiUser {
  return { id: user.id, username: user.username, nickname: user.nickname }
}

function requireSession(): { user: MockUser; family: MockFamily | null; membership: MockMember | null } {
  const database = getDb()
  const token = readToken()
  const userId = token ? database.tokens[token] : undefined
  if (!userId) {
    if (token) window.dispatchEvent(new CustomEvent('wa:unauthorized'))
    throw new ApiError(401, 'UNAUTHORIZED', '登录状态已失效')
  }
  const user = findUser(userId)
  for (const family of database.families) {
    const membership = family.members.find((member) => member.userId === user.id)
    if (membership) return { user, family, membership }
  }
  return { user, family: null, membership: null }
}

function requireFamily(): { user: MockUser; family: MockFamily; membership: MockMember } {
  const session = requireSession()
  if (!session.family || !session.membership) {
    throw new ApiError(404, 'NOT_FOUND', '你还没有加入家庭')
  }
  return { user: session.user, family: session.family, membership: session.membership }
}

function requireAdmin(): { user: MockUser; family: MockFamily; membership: MockMember } {
  const session = requireFamily()
  if (session.membership.role !== 'admin') {
    throw new ApiError(403, 'FORBIDDEN', '仅管理员可以执行此操作')
  }
  return session
}

function familyInfo(family: MockFamily, role: Role): FamilyInfo {
  const info: FamilyInfo = { id: family.id, name: family.name, role }
  if (role === 'admin') {
    info.inviteCode = family.inviteCode
    info.inviteExpiresAt = family.inviteExpiresAt
  }
  return info
}

function deviceForUser(family: MockFamily, userId: string): MockDevice | null {
  return getDb().devices.find((device) => device.familyId === family.id && device.userId === userId) ?? null
}

function decorateDevice(device: MockDevice): DeviceInfo {
  const owner = getDb().users.find((user) => user.id === device.userId)
  return {
    id: device.id,
    name: device.name,
    platform: device.platform,
    model: device.model,
    osVersion: device.osVersion,
    appVersion: device.appVersion,
    online: device.online,
    lastSeenAt: device.lastSeenAt,
    lastLocation: device.lastLocation,
    userId: device.userId,
    ownerNickname: owner?.nickname ?? '家人',
  }
}

function liveLocation(device: MockDevice, index: number): LocationPoint | null {
  if (!device.online) return device.lastLocation
  const t = Date.now() / 1000
  const lat = device.anchor.lat + Math.sin(t / 420 + index * 1.7) * 0.00055
  const lng = device.anchor.lng + Math.cos(t / 520 + index * 2.1) * 0.0007
  const point: LocationPoint = {
    lat,
    lng,
    accuracy: device.accuracy,
    speed: device.speed,
    battery: device.battery,
    bearing: 90,
    provider: 'amap',
    recordedAt: new Date().toISOString(),
  }
  device.lastLocation = point
  device.lastSeenAt = point.recordedAt
  return point
}

function touchLivePositions(family: MockFamily): void {
  family.members.forEach((member, index) => {
    const device = deviceForUser(family, member.userId)
    if (device) liveLocation(device, index)
  })
}

function toMemberView(family: MockFamily, member: MockMember): MemberView {
  const user = findUser(member.userId)
  const device = deviceForUser(family, member.userId)
  return {
    userId: user.id,
    username: user.username,
    nickname: user.nickname,
    role: member.role,
    sharingPaused: member.sharingPaused,
    online: device?.online ?? false,
    lastLocation: device?.lastLocation ?? null,
    deviceId: device?.id ?? null,
    lastSeenAt: device?.lastSeenAt ?? null,
  }
}

/* -------------------------------------------------------------------------- */
/*  Deterministic trail generator                                              */
/* -------------------------------------------------------------------------- */

function hashString(value: string): number {
  let hash = 2166136261
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state / 4294967296
  }
}

function isoAtLocalMinutes(dateKey: string, minutes: number): string {
  const base = parseDateValue(dateKey)
  const date = new Date(
    base.getFullYear(),
    base.getMonth(),
    base.getDate(),
    0,
    minutes,
    0,
    0,
  )
  return date.toISOString()
}

function buildTrail(device: MockDevice, dateKey: string): LocationPoint[] {
  const now = new Date()
  const todayKey = localDateValue(now)
  const previous = dateKey < todayKey
  if (dateKey > todayKey) return []

  const nowMinutes = now.getHours() * 60 + now.getMinutes()
  let endMinute = previous ? 19 * 60 + 10 : Math.min(19 * 60 + 10, nowMinutes - 2)
  let startMinute = 7 * 60 + 30
  if (endMinute <= startMinute + 25) startMinute = Math.max(5, endMinute - 160)
  if (endMinute < 15) return []

  const rand = seededRandom(hashString(`${device.id}:${dateKey}`))
  const anchor = device.anchor
  const spreadLat = 0.012 + rand() * 0.008
  const spreadLng = 0.016 + rand() * 0.012
  const waypoints = [
    { lat: anchor.lat, lng: anchor.lng },
    {
      lat: clamp(anchor.lat + (rand() - 0.5) * spreadLat, 30.263, 30.295),
      lng: clamp(anchor.lng + (rand() - 0.35) * spreadLng, 120.119, 120.181),
    },
    {
      lat: clamp(anchor.lat + (rand() - 0.4) * spreadLat, 30.263, 30.295),
      lng: clamp(anchor.lng + (rand() - 0.65) * spreadLng, 120.119, 120.181),
    },
    {
      lat: clamp(anchor.lat + (rand() - 0.5) * 0.004, 30.263, 30.295),
      lng: clamp(anchor.lng + (rand() - 0.5) * 0.005, 120.119, 120.181),
    },
  ]

  const durationMinutes = endMinute - startMinute
  const count = clamp(Math.round(durationMinutes / 6), 28, 180)
  const phase = rand() * Math.PI * 2
  const points: LocationPoint[] = []

  for (let i = 0; i < count; i += 1) {
    const fraction = count === 1 ? 0 : i / (count - 1)
    const along = fraction * (waypoints.length - 1)
    const segment = Math.min(waypoints.length - 2, Math.floor(along))
    const localT = along - segment
    const eased = localT < 0.5 ? 2 * localT * localT : 1 - ((-2 * localT + 2) ** 2) / 2
    const from = waypoints[segment]!
    const to = waypoints[segment + 1]!
    const wobble = Math.sin(fraction * Math.PI * 5 + phase) * 0.00045
    const jitterLat = (rand() - 0.5) * 0.00018
    const jitterLng = (rand() - 0.5) * 0.0002
    const lat = from.lat + (to.lat - from.lat) * eased + wobble + jitterLat
    const lng = from.lng + (to.lng - from.lng) * eased + wobble * 0.6 + jitterLng
    const minutes = Math.round(startMinute + fraction * durationMinutes)
    const stopped = Math.sin(fraction * Math.PI * 7 + phase) > 0.86
    points.push({
      id: `p_${device.id}_${dateKey}_${i}`,
      lat,
      lng,
      accuracy: Math.round(9 + rand() * 22),
      speed: stopped ? 0 : Number((2 + rand() * 9).toFixed(1)),
      battery: Math.max(12, Math.round(92 - fraction * 28)),
      bearing: Math.round((rand() * 360) | 0),
      provider: 'amap',
      recordedAt: isoAtLocalMinutes(dateKey, minutes),
    })
  }

  return points
}

/* -------------------------------------------------------------------------- */
/*  Follow / alert helpers                                                    */
/* -------------------------------------------------------------------------- */

const RULE_DEFAULTS: Record<AlertRuleType, Record<string, unknown>> = {
  MOVEMENT: { thresholdMeters: 500, cooldownMinutes: 30 },
  STALE: { hours: 12 },
  LOW_BATTERY: { percent: 20 },
}

function toRuleInfo(rule: MockRule): AlertRuleInfo {
  return {
    id: rule.id,
    type: rule.type,
    enabled: rule.enabled,
    config: rule.config as unknown as AlertRuleConfig,
    lastFiredAt: rule.lastFiredAt,
  }
}

function toFollowInfo(follow: MockFollow): FollowInfo {
  const target = findUser(follow.targetId)
  return {
    id: follow.id,
    target: { userId: target.id, nickname: target.nickname },
    createdAt: follow.createdAt,
    rules: follow.rules.map(toRuleInfo),
  }
}

function toAlertInfo(alert: MockAlert): AlertInfo {
  return {
    id: alert.id,
    type: alert.type,
    target: alert.target,
    title: alert.title,
    body: alert.body,
    silent: alert.silent,
    data: alert.data,
    occurredAt: alert.occurredAt,
    createdAt: alert.createdAt,
    readAt: alert.readAt,
  }
}

function requireOwnedFollow(followId: string): MockFollow {
  const { user } = requireSession()
  const follow = getDb().follows.find(
    (item) => item.id === followId && item.followerId === user.id,
  )
  if (!follow) throw new ApiError(404, 'NOT_FOUND', '关注不存在')
  return follow
}

/* -------------------------------------------------------------------------- */
/*  API implementation                                                        */
/* -------------------------------------------------------------------------- */

export const mockApi: ApiSurface = {
  auth: {
    async login(input: LoginInput): Promise<AuthResult> {
      await sleep(420)
      const database = getDb()
      const username = input.username.trim()
      const user = database.users.find((item) => item.username.toLowerCase() === username.toLowerCase())
      if (!user || user.password !== input.password) {
        throw new ApiError(401, 'UNAUTHORIZED', '用户名或密码不正确')
      }
      const token = `mock_${user.id}_${Math.random().toString(36).slice(2, 10)}`
      database.tokens[token] = user.id
      persist()
      const family = database.families.find((item) =>
        item.members.some((member) => member.userId === user.id),
      )
      return { token, user: publicUser(user), familyId: family?.id ?? null }
    },

    async register(input: RegisterInput): Promise<AuthResult> {
      await sleep(520)
      const database = getDb()
      const username = input.username.trim()
      const nickname = input.nickname.trim()
      const password = input.password
      if (username.length < 3 || username.length > 20) {
        throw new ApiError(400, 'VALIDATION', '用户名需要 3–20 位')
      }
      if (!nickname || nickname.length > 20) {
        throw new ApiError(400, 'VALIDATION', '昵称不能为空，最多 20 个字符')
      }
      if (password.length < 8) {
        throw new ApiError(400, 'VALIDATION', '密码至少 8 位')
      }
      if (database.users.some((item) => item.username.toLowerCase() === username.toLowerCase())) {
        throw new ApiError(409, 'CONFLICT', '用户名已被占用')
      }

      let inviteFamily: MockFamily | null = null
      if (input.inviteCode?.trim()) {
        const code = input.inviteCode.trim()
        inviteFamily =
          database.families.find((item) => item.inviteCode === code) ?? null
        if (!inviteFamily) throw new ApiError(400, 'INVITE_INVALID', '邀请码无效')
        if (new Date(inviteFamily.inviteExpiresAt).getTime() < Date.now()) {
          throw new ApiError(400, 'INVITE_EXPIRED', '邀请码已过期')
        }
      }

      const user: MockUser = {
        id: newId('u'),
        username,
        nickname,
        password,
      }
      database.users.push(user)
      if (inviteFamily) {
        inviteFamily.members.push({
          userId: user.id,
          role: 'member',
          sharingPaused: false,
          joinedAt: new Date().toISOString(),
        })
      }
      const token = `mock_${user.id}_${Math.random().toString(36).slice(2, 10)}`
      database.tokens[token] = user.id
      persist()
      return { token, user: publicUser(user), familyId: inviteFamily?.id ?? null }
    },

    async me(): Promise<MeResponse> {
      await sleep(220)
      const session = requireSession()
      const devices = session.family
        ? getDb()
            .devices.filter(
              (device) => device.familyId === session.family!.id && device.userId === session.user.id,
            )
            .map(decorateDevice)
        : []
      return {
        user: publicUser(session.user),
        family: session.family
          ? {
              id: session.family.id,
              name: session.family.name,
              role: session.membership!.role,
            }
          : null,
        devices,
      }
    },
  },

  families: {
    async create(name: string): Promise<{ family: FamilyInfo }> {
      await sleep(460)
      const session = requireSession()
      if (session.family) throw new ApiError(409, 'ALREADY_IN_FAMILY', '你已经在家庭中了')
      const familyName = name.trim()
      if (!familyName || familyName.length > 30) {
        throw new ApiError(400, 'VALIDATION', '家庭名需要 1–30 个字符')
      }
      const family: MockFamily = {
        id: newId('f'),
        name: familyName,
        inviteCode: generateInviteCode(),
        inviteExpiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
        createdAt: new Date().toISOString(),
        clearedAt: null,
        members: [
          {
            userId: session.user.id,
            role: 'admin',
            sharingPaused: false,
            joinedAt: new Date().toISOString(),
          },
        ],
      }
      getDb().families.push(family)
      persist()
      return { family: familyInfo(family, 'admin') }
    },

    async join(code: string): Promise<{ family: FamilyInfo }> {
      await sleep(460)
      const session = requireSession()
      if (session.family) throw new ApiError(409, 'ALREADY_IN_FAMILY', '你已经在家庭中了')
      const family = getDb().families.find((item) => item.inviteCode === code.trim())
      if (!family) throw new ApiError(400, 'INVITE_INVALID', '邀请码无效')
      if (new Date(family.inviteExpiresAt).getTime() < Date.now()) {
        throw new ApiError(400, 'INVITE_EXPIRED', '邀请码已过期')
      }
      family.members.push({
        userId: session.user.id,
        role: 'member',
        sharingPaused: false,
        joinedAt: new Date().toISOString(),
      })
      persist()
      return { family: familyInfo(family, 'member') }
    },

    async me(): Promise<FamilyResponse> {
      await sleep(360)
      const { family, membership } = requireFamily()
      touchLivePositions(family)
      const members: FamilyMemberInfo[] = family.members.map((member) => {
        const view = toMemberView(family, member)
        return {
          userId: view.userId,
          username: view.username,
          nickname: view.nickname,
          role: view.role,
          sharingPaused: view.sharingPaused,
          online: view.online,
          lastLocation: view.lastLocation,
        }
      })
      return { family: familyInfo(family, membership.role), members }
    },

    async resetInvite(): Promise<InviteResetResponse> {
      await sleep(360)
      const { family } = requireAdmin()
      family.inviteCode = generateInviteCode()
      family.inviteExpiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
      persist()
      return { inviteCode: family.inviteCode, expiresAt: family.inviteExpiresAt }
    },

    async removeMember(userId: string): Promise<void> {
      await sleep(380)
      const { user, family } = requireAdmin()
      if (userId === user.id) throw new ApiError(400, 'VALIDATION', '不能移除自己')
      const target = family.members.find((member) => member.userId === userId)
      if (!target) throw new ApiError(404, 'NOT_FOUND', '成员不存在')
      family.members = family.members.filter((member) => member.userId !== userId)
      getDb().devices = getDb().devices.filter(
        (device) => !(device.familyId === family.id && device.userId === userId),
      )
      persist()
    },

    async leave(): Promise<void> {
      await sleep(380)
      const { user, family, membership } = requireFamily()
      if (membership.role === 'admin') {
        throw new ApiError(403, 'FORBIDDEN', '管理员需先转让或解散家庭')
      }
      family.members = family.members.filter((member) => member.userId !== user.id)
      getDb().devices = getDb().devices.filter(
        (device) => !(device.familyId === family.id && device.userId === user.id),
      )
      persist()
    },
  },

  devices: {
    async list(): Promise<DevicesResponse> {
      await sleep(300)
      const { family } = requireFamily()
      const devices = getDb()
        .devices.filter((device) => device.familyId === family.id)
        .map(decorateDevice)
      return { devices }
    },

    async create(input: CreateDeviceInput): Promise<CreateDeviceResponse> {
      await sleep(420)
      const { family, user } = requireFamily()
      const name = input.name.trim()
      if (!name) throw new ApiError(400, 'VALIDATION', '设备名不能为空')
      const device: MockDevice = {
        id: newId('d'),
        userId: user.id,
        familyId: family.id,
        name,
        platform: input.platform || 'android',
        model: input.model || 'Android 设备',
        osVersion: input.osVersion || '—',
        appVersion: input.appVersion || '0.1.0',
        online: false,
        lastSeenAt: null,
        lastLocation: null,
        anchor: { lat: 30.279, lng: 120.1294 },
        battery: 100,
        accuracy: 20,
        speed: 0,
      }
      getDb().devices.push(device)
      persist()
      return {
        device: decorateDevice(device),
        deviceToken: `mock_device_${Math.random().toString(36).slice(2, 18)}`,
      }
    },

    async rename(id: string, name: string): Promise<{ device: DeviceInfo }> {
      await sleep(300)
      const { family, user, membership } = requireFamily()
      const device = getDb().devices.find(
        (item) => item.id === id && item.familyId === family.id,
      )
      if (!device) throw new ApiError(404, 'NOT_FOUND', '设备不存在')
      if (membership.role !== 'admin' && device.userId !== user.id) {
        throw new ApiError(403, 'FORBIDDEN', '只能重命名自己的设备')
      }
      const nextName = name.trim()
      if (!nextName) throw new ApiError(400, 'VALIDATION', '设备名不能为空')
      device.name = nextName
      persist()
      return { device: decorateDevice(device) }
    },

    async remove(id: string): Promise<void> {
      await sleep(320)
      const { family, user, membership } = requireFamily()
      const device = getDb().devices.find(
        (item) => item.id === id && item.familyId === family.id,
      )
      if (!device) throw new ApiError(404, 'NOT_FOUND', '设备不存在')
      if (membership.role !== 'admin' && device.userId !== user.id) {
        throw new ApiError(403, 'FORBIDDEN', '只能解绑自己的设备')
      }
      getDb().devices = getDb().devices.filter((item) => item.id !== id)
      persist()
    },
  },

  locations: {
    async latest(): Promise<LatestResponse> {
      await sleep(130)
      const { family } = requireFamily()
      touchLivePositions(family)
      const members: LatestMemberInfo[] = family.members.map((member) => {
        const device = deviceForUser(family, member.userId)
        return {
          userId: member.userId,
          deviceId: device?.id ?? null,
          online: device?.online ?? false,
          lastSeenAt: device?.lastSeenAt ?? null,
          lastLocation: device?.lastLocation ?? null,
        }
      })
      return { members }
    },

    async history(deviceId, from, to, limit = 500): Promise<HistoryResponse> {
      await sleep(440)
      const { family } = requireFamily()
      const device = getDb().devices.find(
        (item) => item.id === deviceId && item.familyId === family.id,
      )
      if (!device) throw new ApiError(404, 'NOT_FOUND', '设备不存在')

      const fromDate = new Date(from)
      const toDate = new Date(to)
      if (Number.isNaN(fromDate.getTime()) || Number.isNaN(toDate.getTime())) {
        throw new ApiError(400, 'VALIDATION', '时间范围无效')
      }
      const dateKey = localDateValue(fromDate)
      let points = buildTrail(device, dateKey)
      if (family.clearedAt) {
        const cleared = new Date(family.clearedAt).getTime()
        points = points.filter((point) => new Date(point.recordedAt).getTime() >= cleared)
      }
      points = points.filter((point) => {
        const time = new Date(point.recordedAt).getTime()
        return time >= fromDate.getTime() && time <= toDate.getTime()
      })
      const capped = Math.max(1, Math.min(5000, limit))
      return { points: points.slice(0, capped) }
    },
  },

  locate: {
    async request(deviceId: string): Promise<LocateCommandResponse> {
      await sleep(300)
      const { family, user } = requireFamily()
      const device = getDb().devices.find(
        (item) => item.id === deviceId && item.familyId === family.id,
      )
      if (!device) throw new ApiError(404, 'NOT_FOUND', '设备不存在')
      if (!device.online) throw new ApiError(409, 'DEVICE_OFFLINE', '设备离线')

      const existing = getDb().commands.find(
        (command) =>
          command.deviceId === deviceId &&
          command.status === 'pending' &&
          Date.now() - new Date(command.createdAt).getTime() < 30_000,
      )
      if (existing) return { commandId: existing.id, deadlineAt: existing.deadlineAt }

      const createdAt = Date.now()
      const command: MockCommand = {
        id: newId('cmd'),
        deviceId,
        familyId: family.id,
        requestedBy: user.id,
        status: 'pending',
        createdAt: new Date(createdAt).toISOString(),
        deadlineAt: new Date(createdAt + 30_000).toISOString(),
        completeAfter: new Date(createdAt + 2_600).toISOString(),
      }
      getDb().commands.push(command)
      persist()
      return { commandId: command.id, deadlineAt: command.deadlineAt }
    },

    async command(commandId: string): Promise<CommandResponse> {
      await sleep(90)
      const { family } = requireFamily()
      const command = getDb().commands.find((item) => item.id === commandId)
      if (!command || command.familyId !== family.id) {
        throw new ApiError(404, 'NOT_FOUND', '定位指令不存在')
      }
      if (command.status === 'pending') {
        const now = Date.now()
        if (now >= new Date(command.completeAfter).getTime()) {
          command.status = 'completed'
          command.completedAt = new Date(now).toISOString()
          const device = getDb().devices.find((item) => item.id === command.deviceId)
          if (device) {
            const lat = device.anchor.lat + (Math.random() - 0.5) * 0.0018
            const lng = device.anchor.lng + (Math.random() - 0.5) * 0.0022
            device.anchor = { lat, lng }
            device.accuracy = 8
            device.battery = Math.max(1, device.battery - 1)
            device.lastSeenAt = command.completedAt
            device.lastLocation = {
              lat,
              lng,
              accuracy: 8,
              speed: 0,
              battery: device.battery,
              bearing: 0,
              provider: 'amap',
              recordedAt: command.completedAt,
            }
          }
          persist()
        } else if (now >= new Date(command.deadlineAt).getTime()) {
          command.status = 'timeout'
          persist()
        }
      }
      return {
        status: command.status,
        ...(command.completedAt ? { completedAt: command.completedAt } : {}),
      }
    },
  },

  follows: {
    async list(): Promise<{ follows: FollowInfo[] }> {
      await sleep(120)
      const { user } = requireSession()
      return {
        follows: getDb()
          .follows.filter((follow) => follow.followerId === user.id)
          .map(toFollowInfo),
      }
    },

    async create(input: CreateFollowInput): Promise<{ follow: FollowInfo }> {
      await sleep(180)
      const { user, family } = requireFamily()
      if (input.targetUserId === user.id) {
        throw new ApiError(400, 'VALIDATION', '不能关注自己')
      }
      if (!family.members.some((member) => member.userId === input.targetUserId)) {
        throw new ApiError(404, 'NOT_FOUND', '家庭成员不存在')
      }

      const database = getDb()
      const existing = database.follows.find(
        (follow) =>
          follow.followerId === user.id && follow.targetId === input.targetUserId,
      )
      if (existing) return { follow: toFollowInfo(existing) }

      const follow: MockFollow = {
        id: newId('f'),
        familyId: family.id,
        followerId: user.id,
        targetId: input.targetUserId,
        createdAt: new Date().toISOString(),
        rules: (input.rules ?? []).map((rule) => ({
          id: newId('r'),
          type: rule.type,
          enabled: true,
          config: { ...RULE_DEFAULTS[rule.type], ...(rule.config ?? {}) },
          lastFiredAt: null,
        })),
      }
      database.follows.push(follow)
      persist()
      return { follow: toFollowInfo(follow) }
    },

    async remove(id: string): Promise<void> {
      await sleep(140)
      const { user } = requireSession()
      const database = getDb()
      const index = database.follows.findIndex(
        (follow) => follow.id === id && follow.followerId === user.id,
      )
      if (index < 0) throw new ApiError(404, 'NOT_FOUND', '关注不存在')
      database.follows.splice(index, 1)
      persist()
    },

    async followers(): Promise<{ followers: FollowerInfo[] }> {
      await sleep(110)
      const { user } = requireSession()
      return {
        followers: getDb()
          .follows.filter((follow) => follow.targetId === user.id)
          .map((follow) => ({
            userId: follow.followerId,
            nickname: findUser(follow.followerId).nickname,
            since: follow.createdAt,
          })),
      }
    },

    async createRule(
      followId: string,
      type: AlertRuleType,
      config?: Record<string, unknown>,
    ): Promise<{ rule: AlertRuleInfo }> {
      await sleep(140)
      const follow = requireOwnedFollow(followId)
      if (follow.rules.some((rule) => rule.type === type)) {
        throw new ApiError(409, 'CONFLICT', '该类型的提醒已存在')
      }
      if (follow.rules.length >= 5) {
        throw new ApiError(400, 'VALIDATION', '最多 5 条提醒规则')
      }
      const rule: MockRule = {
        id: newId('r'),
        type,
        enabled: true,
        config: { ...RULE_DEFAULTS[type], ...(config ?? {}) },
        lastFiredAt: null,
      }
      follow.rules.push(rule)
      persist()
      return { rule: toRuleInfo(rule) }
    },

    async updateRule(
      followId: string,
      ruleId: string,
      patch: { enabled?: boolean; config?: Record<string, unknown> },
    ): Promise<{ rule: AlertRuleInfo }> {
      await sleep(120)
      const follow = requireOwnedFollow(followId)
      const rule = follow.rules.find((item) => item.id === ruleId)
      if (!rule) throw new ApiError(404, 'NOT_FOUND', '提醒规则不存在')
      if (patch.enabled !== undefined) rule.enabled = patch.enabled
      if (patch.config) rule.config = { ...rule.config, ...patch.config }
      persist()
      return { rule: toRuleInfo(rule) }
    },

    async deleteRule(followId: string, ruleId: string): Promise<void> {
      await sleep(120)
      const follow = requireOwnedFollow(followId)
      const index = follow.rules.findIndex((rule) => rule.id === ruleId)
      if (index < 0) throw new ApiError(404, 'NOT_FOUND', '提醒规则不存在')
      follow.rules.splice(index, 1)
      persist()
    },
  },

  alerts: {
    async list(options: AlertListOptions = {}): Promise<AlertsResponse> {
      await sleep(100)
      const { user } = requireSession()
      const mine = getDb().alerts.filter((alert) => alert.userId === user.id)
      let items = mine
      if (options.unreadOnly) items = items.filter((alert) => alert.readAt === null)
      if (options.after) {
        items = items.filter((alert) => alert.createdAt > options.after!)
      }
      if (options.before) {
        items = items.filter((alert) => alert.createdAt < options.before!)
      }
      items = items
        .slice()
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, options.limit ?? 50)
      return {
        alerts: items.map(toAlertInfo),
        unreadCount: mine.filter((alert) => alert.readAt === null).length,
      }
    },

    async markRead(id: string): Promise<void> {
      await sleep(90)
      const { user } = requireSession()
      const alert = getDb().alerts.find(
        (item) => item.id === id && item.userId === user.id,
      )
      if (!alert) throw new ApiError(404, 'NOT_FOUND', '消息不存在')
      if (!alert.readAt) alert.readAt = new Date().toISOString()
      persist()
    },

    async markAllRead(): Promise<{ updated: number }> {
      await sleep(140)
      const { user } = requireSession()
      const now = new Date().toISOString()
      let updated = 0
      for (const alert of getDb().alerts) {
        if (alert.userId === user.id && !alert.readAt) {
          alert.readAt = now
          updated += 1
        }
      }
      persist()
      return { updated }
    },
  },
}

/** Web-only demo action: update the seeded nickname (no M1 profile endpoint). */
export async function mockUpdateNickname(nickname: string): Promise<void> {
  await sleep(220)
  const { user } = requireSession()
  const next = nickname.trim()
  if (!next || next.length > 20) {
    throw new ApiError(400, 'VALIDATION', '昵称需要 1–20 个字符')
  }
  user.nickname = next
  persist()
}

/** Web-only demo action: clear seeded history (there is no M1 endpoint for it). */
export async function mockClearFamilyHistory(): Promise<void> {
  await sleep(320)
  const { family } = requireAdmin()
  family.clearedAt = new Date().toISOString()
  persist()
}

