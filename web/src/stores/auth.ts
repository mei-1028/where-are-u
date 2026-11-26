import { defineStore } from 'pinia'
import { api } from '@/api'
import type { ApiUser, AuthResult, LoginInput, RegisterInput } from '@/api/types'
import { isUnauthorized } from '@/lib/errors'
import {
  clearApiBaseOverride,
  readCachedFamilyId,
  readCachedUser,
  readToken,
  writeCachedFamilyId,
  writeCachedUser,
  writeToken,
} from '@/lib/session'
import { useFamilyStore } from './family'

interface AuthState {
  token: string | null
  user: ApiUser | null
  familyId: string | null
  initialized: boolean
  /** Invalidates in-flight session checks after logout/login. */
  epoch: number
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: readToken(),
    user: readCachedUser(),
    familyId: readCachedFamilyId(),
    initialized: false,
    epoch: 0,
  }),

  getters: {
    isAuthenticated: (state): boolean => Boolean(state.token),
    displayName: (state): string => state.user?.nickname ?? '家人',
  },

  actions: {
    async ensureSession(): Promise<void> {
      if (this.initialized) return
      const epoch = this.epoch
      const token = this.token
      if (!token) {
        this.initialized = true
        return
      }
      try {
        const me = await api.auth.me()
        if (this.epoch !== epoch || this.token !== token) return
        this.user = me.user
        this.familyId = me.family?.id ?? null
        writeCachedUser(me.user)
        writeCachedFamilyId(this.familyId)
      } catch (err) {
        if (this.epoch !== epoch || this.token !== token) return
        if (isUnauthorized(err)) {
          this.clearSession()
          this.initialized = true
          return
        }
        // Network failures keep the cached identity so the shell can render
        // and pages can show their own offline/error states.
      } finally {
        if (this.epoch === epoch || !this.token) this.initialized = true
      }
    },

    applyResult(result: AuthResult): void {
      this.epoch += 1
      this.token = result.token
      this.user = result.user
      this.familyId = result.familyId
      writeToken(result.token)
      writeCachedUser(result.user)
      writeCachedFamilyId(result.familyId)
    },

    async login(input: LoginInput): Promise<AuthResult> {
      const result = await api.auth.login(input)
      this.applyResult(result)
      this.initialized = true
      return result
    },

    async register(input: RegisterInput): Promise<AuthResult> {
      const result = await api.auth.register(input)
      this.applyResult(result)
      this.initialized = true
      return result
    },

    setFamilyId(familyId: string | null): void {
      this.familyId = familyId
      writeCachedFamilyId(familyId)
    },

    updateNickname(nickname: string): void {
      if (!this.user) return
      this.user = { ...this.user, nickname }
      writeCachedUser(this.user)
    },

    clearSession(): void {
      this.epoch += 1
      this.token = null
      this.user = null
      this.familyId = null
      writeToken(null)
      writeCachedUser(null)
      writeCachedFamilyId(null)
    },

    logout(): void {
      this.clearSession()
      clearApiBaseOverride()
      useFamilyStore().reset()
    },
  },
})
