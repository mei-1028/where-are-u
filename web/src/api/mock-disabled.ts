import type { ApiSurface } from './types'

/** Placeholder module used in clean builds so mock data never ships. */
export const mockDemoInfo = null

function unavailable(): Promise<never> {
  return Promise.reject(new Error('Mock API is not available in this build.'))
}

export const mockApi = {} as ApiSurface

export function mockClearFamilyHistory(): Promise<void> {
  return unavailable()
}

export function mockUpdateNickname(_nickname: string): Promise<void> {
  return unavailable()
}
