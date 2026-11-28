import { defineStore } from 'pinia'
import { api } from '@/api'
import type { AlertInfo } from '@/api/types'
import { showAlertNotification } from '@/lib/notify'
import { useUiStore } from './ui'

const POLL_MS = 10_000
const MAX_ITEMS = 100

let timer: number | null = null

interface AlertsState {
  items: AlertInfo[]
  unreadCount: number
  loaded: boolean
  loading: boolean
  error: string | null
  started: boolean
}

export const useAlertsStore = defineStore('alerts', {
  state: (): AlertsState => ({
    items: [],
    unreadCount: 0,
    loaded: false,
    loading: false,
    error: null,
    started: false,
  }),

  getters: {
    hasUnread: (state) => state.unreadCount > 0,
  },

  actions: {
    async load(force = false): Promise<void> {
      if (this.loading) return
      if (!force && this.loaded) return
      this.loading = true
      try {
        const response = await api.alerts.list({ limit: 50 })
        this.apply(response.alerts, response.unreadCount, { notify: false })
        this.loaded = true
        this.error = null
      } catch (err) {
        this.error = err instanceof Error ? err.message : '消息加载失败'
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded(): Promise<void> {
      await this.load(false)
    },

    /** Fetches only what arrived since the newest item we already have. */
    async poll(): Promise<void> {
      if (document.visibilityState !== 'visible') return
      const cursor = this.items[0]?.createdAt
      try {
        const response = cursor
          ? await api.alerts.list({ after: cursor, limit: 50 })
          : await api.alerts.list({ limit: 50 })
        this.apply(response.alerts, response.unreadCount, { notify: true })
        this.loaded = true
        this.error = null
      } catch {
        // A transient poll failure must not clear the badge or show an error.
      }
    },

    apply(
      incoming: AlertInfo[],
      unreadCount: number,
      options: { notify: boolean },
    ): void {
      const known = new Set(this.items.map((item) => item.id))
      const fresh = incoming.filter((item) => !known.has(item.id))

      if (fresh.length > 0) {
        this.items = [...fresh, ...this.items]
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
          .slice(0, MAX_ITEMS)
      }
      this.unreadCount = unreadCount

      if (!options.notify || !this.loaded || fresh.length === 0) return

      const ui = useUiStore()
      const chronological = fresh
        .slice()
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      for (const alert of chronological) {
        // Night-time low-battery alerts are inbox-only by design.
        if (alert.silent) continue
        if (document.visibilityState === 'visible') {
          ui.toast(`${alert.title} · ${alert.body}`, 'info', 5200)
        } else {
          showAlertNotification(alert)
        }
      }
    },

    async markRead(id: string): Promise<void> {
      const item = this.items.find((alert) => alert.id === id)
      if (item && !item.readAt) {
        item.readAt = new Date().toISOString()
        this.unreadCount = Math.max(0, this.unreadCount - 1)
      }
      try {
        await api.alerts.markRead(id)
      } catch {
        await this.load(true)
      }
    },

    async markAllRead(): Promise<void> {
      const now = new Date().toISOString()
      for (const item of this.items) {
        if (!item.readAt) item.readAt = now
      }
      this.unreadCount = 0
      try {
        await api.alerts.markAllRead()
      } catch {
        await this.load(true)
      }
    },

    startPolling(): void {
      if (timer !== null) return
      this.started = true
      void this.load(true)
      timer = window.setInterval(() => {
        void this.poll()
      }, POLL_MS)
      document.addEventListener('visibilitychange', this.onVisibility)
    },

    stopPolling(): void {
      if (timer !== null) {
        window.clearInterval(timer)
        timer = null
      }
      this.started = false
      document.removeEventListener('visibilitychange', this.onVisibility)
    },

    onVisibility(): void {
      if (document.visibilityState === 'visible') void this.poll()
    },

    reset(): void {
      this.stopPolling()
      this.items = []
      this.unreadCount = 0
      this.loaded = false
      this.loading = false
      this.error = null
    },
  },
})
