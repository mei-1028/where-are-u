import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  API_BASE_KEY,
  FAMILY_KEY,
  setApiBaseOverride,
  writeCachedFamilyId,
  writeCachedUser,
  writeToken,
} from '@/lib/session'
import { useAuthStore } from './auth'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('auth logout', () => {
  it('clears the session and the advanced server override', () => {
    setApiBaseOverride('https://api.example.test/api')
    writeToken('token-1')
    writeCachedUser({ id: 'u1', username: 'chen', nickname: '阿哲' })
    writeCachedFamilyId('f1')

    const auth = useAuthStore()
    expect(auth.token).toBe('token-1')
    auth.logout()

    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(auth.familyId).toBeNull()
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
    expect(localStorage.getItem(FAMILY_KEY)).toBeNull()
  })
})
