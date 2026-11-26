import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { FamilyInfo, FamilyMemberInfo, LatestMemberInfo, LocationPoint } from '@/api/types'

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  latest: vi.fn(),
  devices: vi.fn(),
}))

vi.mock('@/api', () => ({
  isMockMode: false,
  api: {
    families: { me: apiMocks.me },
    locations: { latest: apiMocks.latest },
    devices: { list: apiMocks.devices },
  },
}))

import { mergeMemberViews, useFamilyStore } from './family'

const family: FamilyInfo = { id: 'f1', name: '陈家', role: 'admin' }

function point(lat: number, lng: number, recordedAt: string): LocationPoint {
  return { lat, lng, accuracy: 10, recordedAt }
}

function member(
  userId: string,
  nickname: string,
  overrides: Partial<FamilyMemberInfo> = {},
): FamilyMemberInfo {
  return {
    userId,
    username: userId,
    nickname,
    role: 'member',
    sharingPaused: false,
    online: false,
    lastLocation: null,
    ...overrides,
  }
}

function latest(
  userId: string,
  online: boolean,
  recordedAt: string,
  overrides: Partial<LatestMemberInfo> = {},
): LatestMemberInfo {
  return {
    userId,
    deviceId: `d_${userId}`,
    online,
    lastSeenAt: recordedAt,
    lastLocation: point(30.27, 120.15, recordedAt),
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  apiMocks.me.mockReset()
  apiMocks.latest.mockReset()
  apiMocks.devices.mockReset()
})

describe('mergeMemberViews', () => {
  it('uses latest positions, device fallbacks and keeps sharingPaused from the roster', () => {
    const m1 = member('u1', '阿哲', { role: 'admin' })
    const m2 = member('u2', '妈妈', { sharingPaused: true })
    const m3 = member('u3', '爸爸')
    const merged = mergeMemberViews({
      members: [m1, m2, m3],
      latest: [
        latest('u1', false, '2026-11-26T10:00:00.000Z'),
        latest('u1', true, '2026-11-26T09:00:00.000Z'),
        latest('removed-user', true, '2026-11-26T08:00:00.000Z'),
      ],
      devices: [
        {
          id: 'd_u2',
          name: '妈妈的小米',
          platform: 'android',
          model: 'Xiaomi 14',
          osVersion: 'Android 15',
          appVersion: '0.1.0',
          online: true,
          lastSeenAt: '2026-11-26T10:05:00.000Z',
          lastLocation: point(30.28, 120.16, '2026-11-26T10:05:00.000Z'),
          userId: 'u2',
        },
      ],
    })

    expect(merged.map((entry) => entry.userId)).toEqual(['u1', 'u2', 'u3'])
    expect(merged[0]?.online).toBe(true)
    expect(merged[0]?.lastLocation?.recordedAt).toBe('2026-11-26T09:00:00.000Z')
    expect(merged[1]?.sharingPaused).toBe(true)
    expect(merged[1]?.deviceId).toBe('d_u2')
    expect(merged[1]?.lastLocation?.recordedAt).toBe('2026-11-26T10:05:00.000Z')
    expect(merged[2]?.deviceId).toBeNull()
  })
})

describe('family store roster refresh', () => {
  it('adds new members, removes departed members and updates sharingPaused', async () => {
    const store = useFamilyStore()
    store.$patch({
      family,
      members: [member('u1', '阿哲'), member('u2', '妈妈')],
      latest: [latest('u1', true, '2026-11-26T10:00:00.000Z'), latest('u2', true, '2026-11-26T10:00:00.000Z')],
    })
    apiMocks.me.mockResolvedValueOnce({
      family,
      members: [
        member('u1', '阿哲', { sharingPaused: true }),
        member('u3', '奶奶', { lastLocation: point(30.28, 120.12, '2026-11-26T11:00:00.000Z') }),
      ],
    })

    const ok = await store.refreshRoster()
    expect(ok).toBe(true)
    expect(store.members.map((entry) => entry.userId)).toEqual(['u1', 'u3'])
    expect(store.members[0]?.sharingPaused).toBe(true)
    expect(store.latest.map((entry) => entry.userId)).toEqual(['u1'])
    expect(store.memberViews.map((entry) => entry.userId)).toEqual(['u1', 'u3'])
  })
})

describe('family store session isolation', () => {
  it('ignores a load response that resolves after reset', async () => {
    let resolveMe!: (value: unknown) => void
    apiMocks.me.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveMe = resolve
        }),
    )
    apiMocks.latest.mockResolvedValue({ members: [] })
    apiMocks.devices.mockResolvedValue({ devices: [] })

    const store = useFamilyStore()
    const pending = store.load(true)
    store.reset()
    resolveMe({ family, members: [member('u1', '旧会话成员')] })
    await pending

    expect(store.generation).toBe(1)
    expect(store.family).toBeNull()
    expect(store.members).toEqual([])
    expect(store.loading).toBe(false)
    expect(apiMocks.latest).not.toHaveBeenCalled()
  })
})
