<script setup lang="ts">
import ModalSheet from './ModalSheet.vue'

withDefaults(
  defineProps<{
    open: boolean
    title: string
    description: string
    confirmLabel?: string
    cancelLabel?: string
    tone?: 'default' | 'danger'
    busy?: boolean
  }>(),
  {
    confirmLabel: '确认',
    cancelLabel: '取消',
    tone: 'default',
    busy: false,
  },
)

const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <ModalSheet :open="open" :title="title" :description="description" @close="emit('cancel')">
    <slot />
    <template #footer>
      <button type="button" class="btn" :disabled="busy" @click="emit('cancel')">
        {{ cancelLabel }}
      </button>
      <button
        type="button"
        class="btn"
        :class="tone === 'danger' ? 'btn--danger-solid' : 'btn--primary'"
        :disabled="busy"
        @click="emit('confirm')"
      >
        <span v-if="busy" class="spinner" />
        {{ confirmLabel }}
      </button>
    </template>
  </ModalSheet>
</template>
