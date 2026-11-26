import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setApiBaseOverride, writeToken } from '@/lib/session'
import { realApi } from './real'

function unauthorizedResponse(): Response {
  return new Response(
    JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'expired' } }),
    { status: 401, headers: { 'Content-Type': 'application/json' } },
  )
}

beforeEach(() => {
  localStorage.clear()
  setApiBaseOverride('https://api.example.test/api')
  writeToken('token-1')
})

describe('real API 401 handling', () => {
  it('dispatches wa:unauthorized only for requests that carried a token', async () => {
    const fetchMock = vi.fn(async () => unauthorizedResponse())
    vi.stubGlobal('fetch', fetchMock)
    const listener = vi.fn()
    window.addEventListener('wa:unauthorized', listener)

    try {
      await expect(realApi.auth.me()).rejects.toMatchObject({
        status: 401,
        code: 'UNAUTHORIZED',
      })
      expect(listener).toHaveBeenCalledTimes(1)
      expect(fetchMock).toHaveBeenCalledWith(
        'https://api.example.test/api/me',
        expect.objectContaining({ method: 'GET' }),
      )
    } finally {
      window.removeEventListener('wa:unauthorized', listener)
    }
  })

  it('does not dispatch for unauthenticated login failures', async () => {
    writeToken(null)
    const fetchMock = vi.fn(
      async (_url: string, _init?: RequestInit): Promise<Response> => unauthorizedResponse(),
    )
    vi.stubGlobal('fetch', fetchMock)
    const listener = vi.fn()
    window.addEventListener('wa:unauthorized', listener)

    try {
      await expect(
        realApi.auth.login({ username: 'chen', password: 'not-a-real-password' }),
      ).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' })
      expect(listener).not.toHaveBeenCalled()
      const init = fetchMock.mock.calls[0]?.[1]
      const headers = init?.headers as Record<string, string> | undefined
      expect(headers?.Authorization).toBeUndefined()
    } finally {
      window.removeEventListener('wa:unauthorized', listener)
    }
  })
})

