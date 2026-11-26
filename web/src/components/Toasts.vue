<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import Icon from './Icon.vue'

const ui = useUiStore()

function iconFor(tone: 'default' | 'success' | 'error' | 'info'): string {
  if (tone === 'success') return 'check'
  if (tone === 'error') return 'alert'
  return 'info'
}
</script>

<template>
  <div
    class="toast-stack"
    :class="{ 'toast-stack--offline': !ui.online }"
    aria-live="polite"
    aria-atomic="false"
  >
    <TransitionGroup name="toast">
      <div
        v-for="toast in ui.toasts"
        :key="toast.id"
        class="toast"
        :class="`toast--${toast.tone}`"
        role="status"
        title="点击关闭"
        @click="ui.dismiss(toast.id)"
      >
        <Icon :name="iconFor(toast.tone)" :size="17" />
        <span>{{ toast.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.24s var(--ease-out);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.98);
}

.toast-leave-active {
  position: absolute;
}
</style>
