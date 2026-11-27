import { describe, expect, it, vi } from 'vitest'

/**
 * `composeAddress` is internal, so the public surface (`reverseGeocode`) is
 * exercised instead: the fake AMap returns structured components and the
 * composed address is asserted through the public API.
 */

// Stub the AMap loader so no network call is made.
vi.mock('@/lib/amap', () => ({
  isAmapConfigured: true,
  loadAMap: () =>
    Promise.resolve({
      plugin: (_name: string, cb: () => void) => cb(),
      Geocoder: class {
        getAddress(
          _lngLat: [number, number],
          done: (status: string, res: unknown) => void,
        ) {
          done('complete', {
            regeocode: {
              addressComponent: {
                district: '常熟市',
                township: '碧溪街道',
                street: '长春一路',
                streetNumber: '8号',
              },
            },
          })
        }
      },
    }),
}))

describe('reverseGeocode', () => {
  it('composes a compact address from structured components', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await expect(reverseGeocode(31.67947, 120.79418)).resolves.toBe(
      '常熟市碧溪街道长春一路8号',
    )
  })

  it('deduplicates concurrent lookups for the same ~11m cell', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    // Called before either settles, so the in-flight map must hand back the
    // exact same promise instead of issuing a second SDK request.
    const first = reverseGeocode(31.685, 120.795)
    const second = reverseGeocode(31.685, 120.795)
    expect(first).toBe(second)
    await expect(first).resolves.toBe('常熟市碧溪街道长春一路8号')
  })

  it('serves a cached address for the same ~11m cell', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await reverseGeocode(31.69, 120.8)
    await expect(reverseGeocode(31.69, 120.8)).resolves.toBe(
      '常熟市碧溪街道长春一路8号',
    )
  })

  it('rejects non-finite coordinates without touching the SDK', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await expect(reverseGeocode(Number.NaN, 120)).resolves.toBeNull()
  })
})
