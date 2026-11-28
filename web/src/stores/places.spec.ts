import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { PlaceInfo } from '@/api/types'

const apiMocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  followsList: vi.fn(),
}))

vi.mock('@/api', () => ({
  isMockMode: false,
  api: {
    places: {
      list: apiMocks.list,
      create: apiMocks.create,
      update: apiMocks.update,
      remove: apiMocks.remove,
    },
    follows: { list: apiMocks.followsList },
  },
}))

import { usePlacesStore } from './places'

function makePlace(id: string, name: string, createdAt: string): PlaceInfo {
  return {
    id,
    name,
    type: 'other',
    lat: 31.6,
    lng: 120.7,
    radiusMeters: 200,
    createdBy: 'u_1',
    createdAt,
    usedByRules: 0,
  }
}

describe('places store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMocks.list.mockReset()
    apiMocks.create.mockReset()
    apiMocks.update.mockReset()
    apiMocks.remove.mockReset()
    apiMocks.followsList.mockReset()
    apiMocks.followsList.mockResolvedValue({ follows: [] })
  })

  it('loads places', async () => {
    apiMocks.list.mockResolvedValueOnce({
      places: [makePlace('p1', '家', '2026-11-01T00:00:00.000Z')],
    })
    const store = usePlacesStore()
    await store.load(true)
    expect(store.items).toHaveLength(1)
    expect(store.items[0]!.name).toBe('家')
    expect(store.byId('p1')?.name).toBe('家')
  })

  it('appends a created place', async () => {
    const created = makePlace('p2', '学校', '2026-11-02T00:00:00.000Z')
    apiMocks.list.mockResolvedValueOnce({
      places: [makePlace('p1', '家', '2026-11-01T00:00:00.000Z')],
    })
    apiMocks.create.mockResolvedValueOnce({ place: created })

    const store = usePlacesStore()
    await store.load(true)
    await store.create({ name: '学校', lat: 31.6, lng: 120.7 })
    expect(store.items.map((item) => item.name)).toEqual(['家', '学校'])
  })

  it('replaces an updated place and refreshes follow rules', async () => {
    const original = makePlace('p1', '家', '2026-11-01T00:00:00.000Z')
    apiMocks.list.mockResolvedValueOnce({ places: [original] })
    apiMocks.update.mockResolvedValueOnce({
      place: { ...original, name: '新家', radiusMeters: 500 },
    })

    const store = usePlacesStore()
    await store.load(true)
    await store.update('p1', { name: '新家', radiusMeters: 500 })

    expect(store.items[0]!.name).toBe('新家')
    expect(store.items[0]!.radiusMeters).toBe(500)
    expect(apiMocks.followsList).toHaveBeenCalledTimes(1)
  })

  it('removes a place and returns the affected rule count', async () => {
    apiMocks.list.mockResolvedValueOnce({
      places: [makePlace('p1', '家', '2026-11-01T00:00:00.000Z')],
    })
    apiMocks.remove.mockResolvedValueOnce({ removedRules: 2 })

    const store = usePlacesStore()
    await store.load(true)
    const removed = await store.remove('p1')

    expect(removed).toBe(2)
    expect(store.items).toHaveLength(0)
    expect(apiMocks.followsList).toHaveBeenCalledTimes(1)
  })

  it('propagates create failures without mutating the list', async () => {
    apiMocks.list.mockResolvedValueOnce({ places: [] })
    apiMocks.create.mockRejectedValueOnce(new Error('这个地点名已经存在'))

    const store = usePlacesStore()
    await store.load(true)
    await expect(
      store.create({ name: '家', lat: 31.6, lng: 120.7 }),
    ).rejects.toThrow('这个地点名已经存在')
    expect(store.items).toHaveLength(0)
  })
})
