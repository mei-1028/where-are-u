import { ref, watch, type Ref } from 'vue'
import type { LocationPoint } from '@/api/types'
import { describeLocation } from '@/lib/geo'
import { reverseGeocode } from '@/lib/geocode'

export interface PlaceLabel {
  /** Human friendly place name. Never contains raw coordinates. */
  label: Ref<string>
  /** True while a remote address lookup is in flight. */
  resolving: Ref<boolean>
}

/**
 * Resolves a readable place name for a location: a local landmark if the point
 * is close to one, otherwise an AMap reverse-geocoded address. Falls back to a
 * neutral label so the UI never shows coordinates.
 */
export function usePlaceLabel(
  point: Ref<LocationPoint | null | undefined> | (() => LocationPoint | null | undefined),
  options: { empty?: string; unknown?: string } = {},
): PlaceLabel {
  const empty = options.empty ?? '暂无位置数据'
  const unknown = options.unknown ?? '位置未知'
  const label = ref('')
  const resolving = ref(false)
  const read = typeof point === 'function' ? point : () => point.value
  let seq = 0

  watch(
    read,
    async (current) => {
      const id = ++seq
      if (!current) {
        label.value = empty
        resolving.value = false
        return
      }

      const local = describeLocation(current.lat, current.lng)
      label.value = local || '正在获取位置…'
      resolving.value = true

      const remote = await reverseGeocode(current.lat, current.lng)
      if (id !== seq) return
      resolving.value = false
      if (remote) label.value = remote
      else if (!local) label.value = unknown
    },
    { immediate: true },
  )

  return { label, resolving }
}
