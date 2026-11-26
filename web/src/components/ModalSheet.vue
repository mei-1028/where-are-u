<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from './Icon.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    wide?: boolean
    hideClose?: boolean
  }>(),
  { description: '', wide: false, hideClose: false },
)

const emit = defineEmits<{ close: [] }>()

const panel = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
let previousOverflow = ''

function close(): void {
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && props.open) {
    event.stopPropagation()
    close()
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      previousOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      document.addEventListener('keydown', onKeydown)
      await nextTick()
      panel.value?.focus()
    } else {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', onKeydown)
      previousFocus?.focus?.()
    }
  },
)

onBeforeUnmount(() => {
  document.body.style.overflow = previousOverflow
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="sheet-fade">
      <div v-if="open" class="modal-backdrop" @click.self="close">
        <div
          ref="panel"
          class="modal"
          :class="{ 'modal--wide': wide }"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          tabindex="-1"
        >
          <div class="modal__head">
            <div>
              <h2 class="modal__title">{{ title }}</h2>
              <p v-if="description" class="modal__desc">{{ description }}</p>
            </div>
            <button
              v-if="!hideClose"
              type="button"
              class="btn btn--icon btn--ghost btn--sm"
              aria-label="关闭"
              @click="close"
            >
              <Icon name="x" :size="18" />
            </button>
          </div>
          <div v-if="$slots.default" class="modal__body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="modal__actions">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.sheet-fade-enter-active,
.sheet-fade-leave-active {
  transition: opacity 0.18s ease;
}

.sheet-fade-enter-from,
.sheet-fade-leave-to {
  opacity: 0;
}

.sheet-fade-enter-active .modal,
.sheet-fade-leave-active .modal {
  transition: transform 0.22s var(--ease-out);
}

.sheet-fade-enter-from .modal {
  transform: translateY(8px) scale(0.985);
}
</style>
