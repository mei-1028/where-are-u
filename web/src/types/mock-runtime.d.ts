declare module '@mock-runtime' {
  import type { ApiSurface } from '@/api/types'

  export interface MockDemoInfo {
    username: string
    password: string
    familyName: string
    inviteCode: string
    memberCount: number
  }

  /** null in clean builds; populated only when VITE_USE_MOCK=true. */
  export const mockDemoInfo: MockDemoInfo | null
  export const mockApi: ApiSurface
  export function mockClearFamilyHistory(): Promise<void>
  export function mockUpdateNickname(nickname: string): Promise<void>
}
