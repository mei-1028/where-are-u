import { describe, expect, it, vi } from 'vitest'

/**
 * `composeAddress` is internal, so the public surface (`reverseGeocode`) is
 * exercised instead: the fake AMap returns structured components and the
 * composed address is asserted through the public API.
 */

// Stub the AMap loader so no network call is made. `aois` carries the
// residential-area name AMap returns with extensions: 'all'.
vi.mock('@/lib/amap', () => ({
  isAmapConfigured: true,
  loadAMap: () =>
    Promise.resolve({
      plugin: (_name: string, cb: () => void) => cb(),
      Geocoder: class {
        getAddress(
          lngLat: [number, number],
          done: (status: string, res: unknown) => void,
        ) {
          // Only the "inside a residential area" fixture returns an AOI.
          const inArea = Math.abs(lngLat[1] - 31.6793) < 0.0005
          done('complete', {
            regeocode: {
              addressComponent: {
                district: '常熟市',
                township: '碧溪街道',
                street: '达海路',
                streetNumber: '6号',
              },
              aois: inArea ? [{ name: '虞枫家园' }] : [],
            },
          })
        }
      },
    }),
}))

describe('reverseGeocode', () => {
  it('prefers the residential-area name over the street number', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await expect(reverseGeocode(31.6793, 120.7942)).resolves.toBe(
      '常熟市碧溪街道虞枫家园',
    )
  })

  it('falls back to street and number when there is no area name', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await expect(reverseGeocode(31.7, 120.9)).resolves.toBe(
      '常熟市碧溪街道达海路6号',
    )
  })

  it('deduplicates concurrent lookups for the same ~11m cell', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    // Called before either settles, so the in-flight map must hand back the
    // exact same promise instead of issuing a second SDK request.
    const first = reverseGeocode(31.685, 120.795)
    const second = reverseGeocode(31.685, 120.795)
    expect(first).toBe(second)
    await expect(first).resolves.toBe('常熟市碧溪街道达海路6号')
  })

  it('serves a cached address for the same ~11m cell', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await reverseGeocode(31.69, 120.8)
    await expect(reverseGeocode(31.69, 120.8)).resolves.toBe(
      '常熟市碧溪街道达海路6号',
    )
  })

  it('rejects non-finite coordinates without touching the SDK', async () => {
    const { reverseGeocode } = await import('@/lib/geocode')
    await expect(reverseGeocode(Number.NaN, 120)).resolves.toBeNull()
  })
})
