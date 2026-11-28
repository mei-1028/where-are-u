export type Role = 'admin' | 'member'

export interface ApiUser {
  id: string
  username: string
  nickname: string
}

export interface AuthResult {
  token: string
  user: ApiUser
  familyId: string | null
}

export interface LoginInput {
  username: string
  password: string
}

export interface RegisterInput {
  username: string
  nickname: string
  password: string
  inviteCode?: string
}

export interface LocationPoint {
  id?: string
  lat: number
  lng: number
  accuracy?: number
  speed?: number
  bearing?: number
  battery?: number
  provider?: string
  recordedAt: string
}

export interface FamilyMemberInfo {
  userId: string
  username: string
  nickname: string
  role: Role
  sharingPaused: boolean
  online: boolean
  lastLocation: LocationPoint | null
}

export interface LatestMemberInfo {
  userId: string
  deviceId: string | null
  online: boolean
  lastSeenAt: string | null
  lastLocation: LocationPoint | null
}

/** UI view model: a family member merged with their freshest device data. */
export interface MemberView extends FamilyMemberInfo {
  deviceId: string | null
  lastSeenAt: string | null
}

export interface FamilyInfo {
  id: string
  name: string
  role: Role
  /** Admin-only. */
  inviteCode?: string
  /** Canonical invite expiry (added server-side); optional for older servers. */
  inviteExpiresAt?: string
}

export interface FamilyResponse {
  family: FamilyInfo
  members: FamilyMemberInfo[]
}

export interface LatestResponse {
  members: LatestMemberInfo[]
}

export interface DeviceInfo {
  id: string
  name: string
  platform: string
  model: string
  osVersion: string
  appVersion: string
  online: boolean
  lastSeenAt: string | null
  lastLocation: LocationPoint | null
  /** Optional extensions used by the web UI for the owner column. */
  userId?: string
  ownerNickname?: string
}

export interface DevicesResponse {
  devices: DeviceInfo[]
}

export interface MeResponse {
  user: ApiUser
  family: { id: string; name: string; role: Role } | null
  devices: DeviceInfo[]
}

export interface CreateDeviceInput {
  name: string
  platform: string
  model: string
  osVersion: string
  appVersion: string
}

export interface CreateDeviceResponse {
  device: DeviceInfo
  deviceToken: string
}

export interface InviteResetResponse {
  inviteCode: string
  expiresAt: string
}

export type LocateStatus = 'pending' | 'completed' | 'timeout' | 'failed'

export interface LocateCommandResponse {
  commandId: string
  deadlineAt: string
}

export interface CommandResponse {
  status: LocateStatus
  completedAt?: string
}

export interface HistoryResponse {
  points: LocationPoint[]
}

export type AlertRuleType = 'MOVEMENT' | 'STALE' | 'LOW_BATTERY' | 'GEOFENCE'

export interface MovementRuleConfig {
  thresholdMeters: number
  cooldownMinutes: number
}

export interface StaleRuleConfig {
  hours: number
}

export interface BatteryRuleConfig {
  percent: number
}

export interface GeofenceWindow {
  /** 1 = Monday ... 7 = Sunday; empty means every day. */
  days: number[]
  start: string
  end: string
}

export interface GeofenceRuleConfig {
  placeId: string
  event: 'ENTER' | 'LEAVE' | 'BOTH'
  window?: GeofenceWindow | null
}

export type AlertRuleConfig =
  | MovementRuleConfig
  | StaleRuleConfig
  | BatteryRuleConfig
  | GeofenceRuleConfig

export interface AlertRuleInfo {
  id: string
  type: AlertRuleType
  enabled: boolean
  config: AlertRuleConfig
  place: { id: string; name: string } | null
  lastFiredAt: string | null
}

export interface FollowInfo {
  id: string
  target: { userId: string; nickname: string }
  createdAt: string
  rules: AlertRuleInfo[]
}

export interface FollowerInfo {
  userId: string
  nickname: string
  since: string
}

export interface AlertData {
  lat?: number
  lng?: number
  accuracy?: number
  distanceMeters?: number
  recordedAt?: string
  lastRecordedAt?: string
  battery?: number
  hours?: number
  placeId?: string
  placeName?: string
  event?: 'ENTER' | 'LEAVE'
}

export interface AlertInfo {
  id: string
  type: AlertRuleType
  target: { userId: string; nickname: string }
  title: string
  body: string
  silent: boolean
  data: AlertData
  occurredAt: string
  createdAt: string
  readAt: string | null
}

export interface AlertsResponse {
  alerts: AlertInfo[]
  unreadCount: number
}

export interface AlertListOptions {
  limit?: number
  unreadOnly?: boolean
  after?: string
  before?: string
}

export interface CreateFollowInput {
  targetUserId: string
  rules?: Array<{ type: AlertRuleType; config?: Record<string, unknown> }>
}

export type PlaceType = 'home' | 'school' | 'company' | 'other'

export interface PlaceInfo {
  id: string
  name: string
  type: PlaceType
  lat: number
  lng: number
  radiusMeters: number
  createdBy: string
  createdAt: string
  usedByRules: number
}

export interface PlaceInput {
  name: string
  type?: PlaceType
  lat: number
  lng: number
  radiusMeters?: number
}

export type PlacePatch = Partial<Omit<PlaceInput, 'lat' | 'lng'>> & {
  lat?: number
  lng?: number
}

export interface ApiSurface {
  auth: {
    login(input: LoginInput): Promise<AuthResult>
    register(input: RegisterInput): Promise<AuthResult>
    me(): Promise<MeResponse>
  }
  families: {
    create(name: string): Promise<{ family: FamilyInfo }>
    join(code: string): Promise<{ family: FamilyInfo }>
    me(): Promise<FamilyResponse>
    resetInvite(): Promise<InviteResetResponse>
    removeMember(userId: string): Promise<void>
    leave(): Promise<void>
  }
  devices: {
    list(): Promise<DevicesResponse>
    create(input: CreateDeviceInput): Promise<CreateDeviceResponse>
    rename(id: string, name: string): Promise<{ device: DeviceInfo }>
    remove(id: string): Promise<void>
  }
  locations: {
    latest(): Promise<LatestResponse>
    history(
      deviceId: string,
      from: string,
      to: string,
      limit?: number,
    ): Promise<HistoryResponse>
  }
  locate: {
    request(deviceId: string): Promise<LocateCommandResponse>
    command(commandId: string): Promise<CommandResponse>
  }
  follows: {
    list(): Promise<{ follows: FollowInfo[] }>
    create(input: CreateFollowInput): Promise<{ follow: FollowInfo }>
    remove(id: string): Promise<void>
    followers(): Promise<{ followers: FollowerInfo[] }>
    createRule(
      followId: string,
      type: AlertRuleType,
      config?: Record<string, unknown>,
    ): Promise<{ rule: AlertRuleInfo }>
    updateRule(
      followId: string,
      ruleId: string,
      patch: { enabled?: boolean; config?: Record<string, unknown> },
    ): Promise<{ rule: AlertRuleInfo }>
    deleteRule(followId: string, ruleId: string): Promise<void>
  }
  alerts: {
    list(options?: AlertListOptions): Promise<AlertsResponse>
    markRead(id: string): Promise<void>
    markAllRead(): Promise<{ updated: number }>
  }
  places: {
    list(): Promise<{ places: PlaceInfo[] }>
    create(input: PlaceInput): Promise<{ place: PlaceInfo }>
    update(id: string, patch: PlacePatch): Promise<{ place: PlaceInfo }>
    remove(id: string): Promise<{ removedRules: number }>
  }
}
