import { defineStore } from 'pinia'
import { api } from '@/api'
import type {
  DeviceInfo,
  FamilyInfo,
  FamilyMemberInfo,
  LatestMemberInfo,
  MemberView,
} from '@/api/types'
import { messageForError } from '@/lib/errors'

export interface FamilyState {
  family: FamilyInfo | null
  members: FamilyMemberInfo[]
  latest: LatestMemberInfo[]
  devices: DeviceInfo[]
  loading: boolean
  latestLoading: boolean
  devicesLoading: boolean
  rosterLoading: boolean
  error: string | null
  latestError: string | null
  devicesError: string | null
  rosterError: string | null
  loadedAt: number
  rosterSyncedAt: number
  lastSyncedAt: number | null
  /** Incremented on reset; stale async results must never cross this boundary. */
  generation: number
}

export interface MemberMergeSource {
  members: FamilyMemberInfo[]
  latest: LatestMemberInfo[]
  devices: DeviceInfo[]
}

let inflightLoad: { generation: number; promise: Promise<void> } | null = null

function chooseLatest(a: LatestMemberInfo | undefined, b: LatestMemberInfo): LatestMemberInfo {
  if (!a) return b
  if (a.online !== b.online) return a.online ? a : b
  const aTime = a.lastLocation ? new Date(a.lastLocation.recordedAt).getTime() : 0
  const bTime = b.lastLocation ? new Date(b.lastLocation.recordedAt).getTime() : 0
  return aTime >= bTime ? a : b
}

/**
 * Merge the authoritative roster with fresher /latest data and device fallbacks.
 * Removed members disappear simply because they are no longer in `members`.
 */
export function mergeMemberViews(state: MemberMergeSource): MemberView[] {
  const latestByUser = new Map<string, LatestMemberInfo>()
  for (const item of state.latest) {
    latestByUser.set(item.userId, chooseLatest(latestByUser.get(item.userId), item))
  }
  const deviceByUser = new Map<string, DeviceInfo>()
  for (const device of state.devices) {
    if (!device.userId) continue
    const current = deviceByUser.get(device.userId)
    if (!current || (device.online && !current.online)) deviceByUser.set(device.userId, device)
  }

  return state.members.map((member) => {
    const latest = latestByUser.get(member.userId)
    const device = deviceByUser.get(member.userId)
    return {
      ...member,
      online: latest?.online ?? device?.online ?? member.online,
      lastLocation: latest?.lastLocation ?? device?.lastLocation ?? member.lastLocation,
      deviceId: latest?.deviceId ?? device?.id ?? null,
      lastSeenAt: latest?.lastSeenAt ?? device?.lastSeenAt ?? null,
    }
  })
}

export const useFamilyStore = defineStore('family', {
  state: (): FamilyState => ({
    family: null,
    members: [],
    latest: [],
    devices: [],
    loading: false,
    latestLoading: false,
    devicesLoading: false,
    rosterLoading: false,
    error: null,
    latestError: null,
    devicesError: null,
    rosterError: null,
    loadedAt: 0,
    rosterSyncedAt: 0,
    lastSyncedAt: null,
    generation: 0,
  }),

  getters: {
    memberViews(state): MemberView[] {
      return mergeMemberViews(state)
    },
    onlineCount(state): number {
      return mergeMemberViews(state).filter((member) => member.online).length
    },
    totalMembers(state): number {
      return state.members.length
    },
    onlineDevices(state): number {
      return state.devices.filter((device) => device.online).length
    },
    isAdmin(state): boolean {
      return state.family?.role === 'admin'
    },
    familyName(state): string {
      return state.family?.name ?? '我的家庭'
    },
    myRole(state): 'admin' | 'member' {
      return state.family?.role ?? 'member'
    },
    isLoaded(state): boolean {
      return Boolean(state.family)
    },
  },

  actions: {
    async load(force = false): Promise<void> {
      const generation = this.generation
      if (inflightLoad && inflightLoad.generation === generation) {
        return inflightLoad.promise
      }
      if (!force && this.family && Date.now() - this.loadedAt < 15_000) return

      const promise = this.performLoad(generation)
      const entry = { generation, promise }
      inflightLoad = entry
      try {
        await promise
      } finally {
        if (inflightLoad === entry) inflightLoad = null
      }
    },

    async performLoad(generation: number): Promise<void> {
      if (generation !== this.generation) return
      this.loading = true
      this.error = null
      try {
        const response = await api.families.me()
        if (generation !== this.generation) return
        this.family = response.family
        this.members = response.members
        this.loadedAt = Date.now()
        this.rosterSyncedAt = Date.now()
        this.rosterError = null

        const [latestResult, devicesResult] = await Promise.allSettled([
          api.locations.latest(),
          api.devices.list(),
        ])
        if (generation !== this.generation) return
        if (latestResult.status === 'fulfilled') {
          const known = new Set(this.members.map((member) => member.userId))
          this.latest = latestResult.value.members
          this.latestError = null
          this.lastSyncedAt = Date.now()
          if (this.latest.some((member) => !known.has(member.userId))) {
            void this.refreshRoster()
          }
        } else {
          this.latestError = messageForError(latestResult.reason, '位置同步失败')
        }
        if (devicesResult.status === 'fulfilled') {
          this.devices = devicesResult.value.devices
          this.devicesError = null
        } else {
          this.devicesError = messageForError(devicesResult.reason, '设备列表加载失败')
        }
      } catch (err) {
        if (generation !== this.generation) return
        this.error = messageForError(err, '家庭信息加载失败')
      } finally {
        if (generation === this.generation) this.loading = false
      }
    },

    async ensureLoaded(force = false): Promise<void> {
      if (this.family && !force && Date.now() - this.loadedAt < 30_000) return
      await this.load(force)
    },

    async refreshLatest(): Promise<boolean> {
      if (this.latestLoading) return false
      const generation = this.generation
      this.latestLoading = true
      try {
        const response = await api.locations.latest()
        if (generation !== this.generation) return false
        this.latest = response.members
        this.latestError = null
        this.lastSyncedAt = Date.now()

        // A member who joined after the last roster fetch can appear here first.
        const known = new Set(this.members.map((member) => member.userId))
        if (response.members.some((member) => !known.has(member.userId))) {
          void this.refreshRoster()
        }
        return true
      } catch (err) {
        if (generation === this.generation) {
          this.latestError = messageForError(err, '位置同步失败')
        }
        return false
      } finally {
        if (generation === this.generation) this.latestLoading = false
      }
    },

    /**
     * Authoritative roster refresh. Keeps sharingPaused and membership current,
     * drops removed members, and prunes their stale /latest entries.
     */
    async refreshRoster(): Promise<boolean> {
      if (this.rosterLoading) return false
      const generation = this.generation
      this.rosterLoading = true
      try {
        const response = await api.families.me()
        if (generation !== this.generation) return false
        this.family = response.family
        this.members = response.members
        this.loadedAt = Date.now()
        this.rosterSyncedAt = Date.now()
        this.rosterError = null

        const ids = new Set(response.members.map((member) => member.userId))
        this.latest = this.latest.filter((member) => ids.has(member.userId))
        return true
      } catch (err) {
        if (generation === this.generation) {
          this.rosterError = messageForError(err, '家庭成员同步失败')
        }
        return false
      } finally {
        if (generation === this.generation) this.rosterLoading = false
      }
    },

    async loadDevices(force = false): Promise<void> {
      if (this.devicesLoading) return
      if (this.devices.length && !force) return
      const generation = this.generation
      this.devicesLoading = true
      try {
        const response = await api.devices.list()
        if (generation !== this.generation) return
        this.devices = response.devices
        this.devicesError = null
      } catch (err) {
        if (generation === this.generation) {
          this.devicesError = messageForError(err, '设备列表加载失败')
        }
      } finally {
        if (generation === this.generation) this.devicesLoading = false
      }
    },

    upsertDevice(device: DeviceInfo): void {
      const index = this.devices.findIndex((item) => item.id === device.id)
      if (index >= 0) this.devices.splice(index, 1, device)
      else this.devices.push(device)
    },

    removeDevice(deviceId: string): void {
      this.devices = this.devices.filter((device) => device.id !== deviceId)
    },

    reset(): void {
      this.generation += 1
      this.family = null
      this.members = []
      this.latest = []
      this.devices = []
      this.loading = false
      this.latestLoading = false
      this.devicesLoading = false
      this.rosterLoading = false
      this.error = null
      this.latestError = null
      this.devicesError = null
      this.rosterError = null
      this.loadedAt = 0
      this.rosterSyncedAt = 0
      this.lastSyncedAt = null
    },
  },
})
