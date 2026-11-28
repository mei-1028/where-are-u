import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AlertInfo } from '@/api/types'

const apiMocks = vi.hoisted(() => ({
  list: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))

vi.mock('@/api', () => ({
  isMockMode: false,
  api: {
    alerts: {
      list: apiMocks.list,
      markRead: apiMocks.markRead,
      markAllRead: apiMocks.markAllRead,
    },
  },
}))

vi.mock('@/lib/notify', () => ({
  showAlertNotification: vi.fn(),
}))

import { useAlertsStore } from './alerts'
import { useUiStore } from './ui'

function makeAlert(id: string, createdAt: string, overrides: Partial<AlertInfo> = {}): AlertInfo {
  return {
    id,
    type: 'MOVEMENT',
    target: { userId: 'u_2', nickname: '妈妈' },
    title: `提醒 ${id}`,
    body: '已移动约 1.2 公里',
    silent: false,
    data: { lat: 31.6, lng: 120.7, distanceMeters: 1200 },
    occurredAt: createdAt,
    createdAt,
    readAt: null,
    ...overrides,
  }
}

describe('alerts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMocks.list.mockReset()
    apiMocks.markRead.mockReset()
    apiMocks.markAllRead.mockReset()
  })

  it('loads the inbox and unread count', async () => {
    apiMocks.list.mockResolvedValueOnce({
      alerts: [makeAlert('a1', '2026-11-28T10:00:00.000Z')],
      unreadCount: 1,
    })
    const store = useAlertsStore()
    await store.load(true)

    expect(store.items).toHaveLength(1)
    expect(store.unreadCount).toBe(1)
    expect(store.loaded).toBe(true)
    expect(apiMocks.list).toHaveBeenCalledWith({ limit: 50 })
  })

  it('polls with the newest cursor and de-duplicates overlapping results', async () => {
    const first = makeAlert('a1', '2026-11-28T10:00:00.000Z')
    const second = makeAlert('a2', '2026-11-28T10:05:00.000Z', { silent: true })

    apiMocks.list.mockResolvedValueOnce({ alerts: [first], unreadCount: 1 })
    const store = useAlertsStore()
    await store.load(true)

    apiMocks.list.mockResolvedValueOnce({ alerts: [second, first], unreadCount: 2 })
    await store.poll()

    expect(apiMocks.list).toHaveBeenLastCalledWith({
      after: first.createdAt,
      limit: 50,
    })
    expect(store.items.map((item) => item.id)).toEqual(['a2', 'a1'])
    expect(store.unreadCount).toBe(2)
  })

  it('shows a toast for a fresh visible alert but not for a silent one', async () => {
    const first = makeAlert('a1', '2026-11-28T10:00:00.000Z')
    const loud = makeAlert('a2', '2026-11-28T10:05:00.000Z')
    const silent = makeAlert('a3', '2026-11-28T10:06:00.000Z', { silent: true })

    apiMocks.list.mockResolvedValueOnce({ alerts: [first], unreadCount: 1 })
    const store = useAlertsStore()
    const toast = vi.spyOn(useUiStore(), 'toast')
    await store.load(true)

    apiMocks.list.mockResolvedValueOnce({ alerts: [silent, loud], unreadCount: 3 })
    await store.poll()

    expect(toast).toHaveBeenCalledTimes(1)
    expect(toast.mock.calls[0]![0]).toContain('提醒 a2')
  })

  it('marks one alert read optimistically', async () => {
    const alert = makeAlert('a1', '2026-11-28T10:00:00.000Z')
    apiMocks.list.mockResolvedValueOnce({ alerts: [alert], unreadCount: 1 })
    apiMocks.markRead.mockResolvedValueOnce(undefined)

    const store = useAlertsStore()
    await store.load(true)
    await store.markRead('a1')

    expect(store.items[0]!.readAt).not.toBeNull()
    expect(store.unreadCount).toBe(0)
    expect(apiMocks.markRead).toHaveBeenCalledWith('a1')
  })

  it('marks everything read', async () => {
    apiMocks.list.mockResolvedValueOnce({
      alerts: [
        makeAlert('a1', '2026-11-28T10:00:00.000Z'),
        makeAlert('a2', '2026-11-28T09:00:00.000Z'),
      ],
      unreadCount: 2,
    })
    apiMocks.markAllRead.mockResolvedValueOnce({ updated: 2 })

    const store = useAlertsStore()
    await store.load(true)
    await store.markAllRead()

    expect(store.items.every((item) => item.readAt !== null)).toBe(true)
    expect(store.unreadCount).toBe(0)
    expect(apiMocks.markAllRead).toHaveBeenCalledTimes(1)
  })

  it('keeps the previous items when loading fails', async () => {
    apiMocks.list.mockResolvedValueOnce({
      alerts: [makeAlert('a1', '2026-11-28T10:00:00.000Z')],
      unreadCount: 1,
    })
    const store = useAlertsStore()
    await store.load(true)

    apiMocks.list.mockRejectedValueOnce(new Error('网络错误'))
    await store.load(true)

    expect(store.items).toHaveLength(1)
    expect(store.error).toBe('网络错误')
  })
})
