import type { ApiSurface } from './types'
import { IS_MOCK_MODE } from '@/lib/session'
import { realApi } from './real'

export const isMockMode = IS_MOCK_MODE

let mockSurfacePromise: Promise<ApiSurface> | null = null

function loadMockSurface(): Promise<ApiSurface> {
  if (!mockSurfacePromise) {
    // '@mock-runtime' is aliased to the seeded implementation in mock-enabled
    // dev/test builds, and to a tiny stub in clean builds. Keeping this import
    // dynamic is what keeps seed data out of the production entry graph.
    mockSurfacePromise = import('@mock-runtime').then((module) => module.mockApi)
  }
  return mockSurfacePromise
}

function resolveSurface(): Promise<ApiSurface> {
  return isMockMode ? loadMockSurface() : Promise.resolve(realApi)
}

type AnyMethod = (...args: unknown[]) => unknown

const groupCache = new Map<string, Record<string, AnyMethod>>()

function callApi(
  group: string,
  method: string,
  args: unknown[],
): Promise<unknown> {
  return resolveSurface().then((surface) => {
    const target = (surface as unknown as Record<string, Record<string, AnyMethod>>)[group]
    const fn = target?.[method]
    if (!fn) {
      return Promise.reject(new Error(`Unknown API method: ${group}.${method}`))
    }
    return fn.apply(target, args)
  })
}

function groupProxy(group: string): Record<string, AnyMethod> {
  const cached = groupCache.get(group)
  if (cached) return cached
  const proxy = new Proxy(
    {},
    {
      get(_target, method) {
        if (typeof method !== 'string') return undefined
        return (...args: unknown[]) => callApi(group, method, args)
      },
    },
  ) as Record<string, AnyMethod>
  groupCache.set(group, proxy)
  return proxy
}

/**
 * Both implementations honour the same ApiSurface. The UI never knows whether
 * it is talking to the seeded demo or a real Fastify server.
 */
export const api: ApiSurface = new Proxy({} as ApiSurface, {
  get(_target, group) {
    return groupProxy(String(group))
  },
})
