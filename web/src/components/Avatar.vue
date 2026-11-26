<script setup lang="ts">
import { computed } from 'vue'
import { avatarTone, initials } from '@/lib/format'

const props = withDefaults(
  defineProps<{
    name: string
    id?: string | null
    size?: number
    muted?: boolean
  }>(),
  { id: null, size: 40, muted: false },
)

const tone = computed(() => avatarTone(props.id ?? props.name))

const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  background: tone.value.bg,
  color: tone.value.ink,
  fontSize: `${Math.max(11, Math.round(props.size * 0.36))}px`,
}))
</script>

<template>
  <span
    class="avatar"
    :class="{ 'avatar--muted': muted }"
    :style="style"
    :title="name"
    aria-hidden="true"
  >
    {{ initials(name) }}
  </span>
</template>

<style scoped>
.avatar {
  display: inline-grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 50%;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0;
  user-select: none;
  transition: opacity 0.2s;
}

.avatar--muted {
  opacity: 0.55;
}
</style>
