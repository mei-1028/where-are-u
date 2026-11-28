import { defineStore } from 'pinia'
import { api } from '@/api'
import type {
  AlertRuleInfo,
  AlertRuleType,
  FollowInfo,
  FollowerInfo,
} from '@/api/types'

interface FollowsState {
  follows: FollowInfo[]
  followers: FollowerInfo[]
  loaded: boolean
  loadedAt: number
  followersLoadedAt: number
  loading: boolean
  error: string | null
}

const CACHE_TTL_MS = 60_000

export const useFollowsStore = defineStore('follows', {
  state: (): FollowsState => ({
    follows: [],
    followers: [],
    loaded: false,
    loadedAt: 0,
    followersLoadedAt: 0,
    loading: false,
    error: null,
  }),

  getters: {
    byTarget:
      (state) =>
      (userId: string): FollowInfo | null =>
        state.follows.find((follow) => follow.target.userId === userId) ?? null,
  },

  actions: {
    async load(force = false): Promise<void> {
      if (this.loading) return
      if (!force && this.loaded && Date.now() - this.loadedAt < CACHE_TTL_MS) return
      this.loading = true
      try {
        const response = await api.follows.list()
        this.follows = response.follows
        this.loaded = true
        this.loadedAt = Date.now()
        this.error = null
      } catch (err) {
        this.error = err instanceof Error ? err.message : '关注列表加载失败'
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded(): Promise<void> {
      await this.load(false)
    },

    async follow(
      targetUserId: string,
      rules: Array<{ type: AlertRuleType; config?: Record<string, unknown> }> = [],
    ): Promise<FollowInfo> {
      const response = await api.follows.create({ targetUserId, rules })
      this.upsert(response.follow)
      return response.follow
    },

    async unfollow(followId: string): Promise<void> {
      await api.follows.remove(followId)
      this.follows = this.follows.filter((follow) => follow.id !== followId)
    },

    async addRule(
      followId: string,
      type: AlertRuleType,
      config?: Record<string, unknown>,
    ): Promise<AlertRuleInfo> {
      const response = await api.follows.createRule(followId, type, config)
      this.patchRule(followId, response.rule)
      return response.rule
    },

    async updateRule(
      followId: string,
      ruleId: string,
      patch: { enabled?: boolean; config?: Record<string, unknown> },
    ): Promise<AlertRuleInfo> {
      const response = await api.follows.updateRule(followId, ruleId, patch)
      this.patchRule(followId, response.rule)
      return response.rule
    },

    async removeRule(followId: string, ruleId: string): Promise<void> {
      await api.follows.deleteRule(followId, ruleId)
      const follow = this.follows.find((item) => item.id === followId)
      if (follow) follow.rules = follow.rules.filter((rule) => rule.id !== ruleId)
    },

    async loadFollowers(force = false): Promise<void> {
      if (!force && Date.now() - this.followersLoadedAt < CACHE_TTL_MS) return
      const response = await api.follows.followers()
      this.followers = response.followers
      this.followersLoadedAt = Date.now()
    },

    upsert(follow: FollowInfo): void {
      const index = this.follows.findIndex((item) => item.id === follow.id)
      if (index >= 0) this.follows.splice(index, 1, follow)
      else this.follows.push(follow)
      this.loaded = true
      this.loadedAt = Date.now()
    },

    patchRule(followId: string, rule: AlertRuleInfo): void {
      const follow = this.follows.find((item) => item.id === followId)
      if (!follow) return
      const index = follow.rules.findIndex((item) => item.id === rule.id)
      if (index >= 0) follow.rules.splice(index, 1, rule)
      else follow.rules.push(rule)
    },

    reset(): void {
      this.follows = []
      this.followers = []
      this.loaded = false
      this.loadedAt = 0
      this.followersLoadedAt = 0
      this.loading = false
      this.error = null
    },
  },
})
