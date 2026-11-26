<script setup lang="ts">
import Icon from './Icon.vue'

type Tone = 'teal' | 'neutral' | 'warn' | 'danger'

withDefaults(
  defineProps<{
    icon?: string
    title: string
    description?: string
    tone?: Tone
    actionLabel?: string
    secondaryLabel?: string
    busy?: boolean
  }>(),
  {
    icon: 'info',
    description: '',
    tone: 'teal',
    actionLabel: '',
    secondaryLabel: '',
    busy: false,
  },
)

const emit = defineEmits<{
  action: []
  secondary: []
}>()
</script>

<template>
  <div class="state-block" role="status">
    <span
      class="state-orb"
      :class="{
        'state-orb--neutral': tone === 'neutral',
        'state-orb--warn': tone === 'warn',
        'state-orb--danger': tone === 'danger',
      }"
    >
      <Icon :name="icon" :size="26" :stroke-width="1.5" />
    </span>
    <h3 class="state-title">{{ title }}</h3>
    <p v-if="description" class="state-desc">{{ description }}</p>
    <slot />
    <div v-if="actionLabel || secondaryLabel" class="state-actions">
      <button
        v-if="actionLabel"
        type="button"
        class="btn btn--primary"
        :disabled="busy"
        @click="emit('action')"
      >
        <span v-if="busy" class="spinner" />
        {{ actionLabel }}
      </button>
      <button
        v-if="secondaryLabel"
        type="button"
        class="btn"
        :disabled="busy"
        @click="emit('secondary')"
      >
        {{ secondaryLabel }}
      </button>
    </div>
  </div>
</template>
