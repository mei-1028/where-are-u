import { defineStore } from 'pinia'
import { api } from '@/api'
import type { PlaceInfo, PlaceInput, PlacePatch } from '@/api/types'
import { useFollowsStore } from './follows'

interface PlacesState {
  items: PlaceInfo[]
  loaded: boolean
  loadedAt: number
  loading: boolean
  error: string | null
}

const CACHE_TTL_MS = 30_000

export const usePlacesStore = defineStore('places', {
  state: (): PlacesState => ({
    items: [],
    loaded: false,
    loadedAt: 0,
    loading: false,
    error: null,
  }),

  getters: {
    byId:
      (state) =>
      (id: string): PlaceInfo | null =>
        state.items.find((place) => place.id === id) ?? null,
  },

  actions: {
    async load(force = false): Promise<void> {
      if (this.loading) return
      if (!force && this.loaded && Date.now() - this.loadedAt < CACHE_TTL_MS) return
      this.loading = true
      try {
        const response = await api.places.list()
        this.items = response.places
        this.loaded = true
        this.loadedAt = Date.now()
        this.error = null
      } catch (err) {
        this.error = err instanceof Error ? err.message : '地点加载失败'
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded(): Promise<void> {
      await this.load(false)
    },

    async create(input: PlaceInput): Promise<PlaceInfo> {
      const response = await api.places.create(input)
      this.items = [...this.items, response.place].sort((a, b) =>
        a.createdAt.localeCompare(b.createdAt),
      )
      this.loaded = true
      this.loadedAt = Date.now()
      return response.place
    },

    async update(id: string, patch: PlacePatch): Promise<PlaceInfo> {
      const response = await api.places.update(id, patch)
      this.items = this.items.map((place) =>
        place.id === id ? response.place : place,
      )
      // The rule DTO carries the place name, so refresh it after a rename.
      await useFollowsStore().load(true)
      return response.place
    },

    async remove(id: string): Promise<number> {
      const response = await api.places.remove(id)
      this.items = this.items.filter((place) => place.id !== id)
      await useFollowsStore().load(true)
      return response.removedRules
    },

    reset(): void {
      this.items = []
      this.loaded = false
      this.loadedAt = 0
      this.loading = false
      this.error = null
    },
  },
})
