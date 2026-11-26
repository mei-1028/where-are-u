import { onBeforeUnmount, onMounted, ref } from 'vue'

/** A shared low-frequency clock so relative times refresh without polling. */
export function useNow(intervalMs = 30_000) {
  const now = ref(Date.now())
  let timer: number | null = null

  onMounted(() => {
    timer = window.setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  })

  onBeforeUnmount(() => {
    if (timer !== null) window.clearInterval(timer)
  })

  return now
}
