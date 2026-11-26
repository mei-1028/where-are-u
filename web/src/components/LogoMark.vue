<script setup lang="ts">
withDefaults(
  defineProps<{
    size?: number
    wordmark?: boolean
    micro?: boolean
  }>(),
  { size: 32, wordmark: false, micro: false },
)
</script>

<template>
  <span class="logo-lockup">
    <span class="logo-tile" :style="{ width: `${size}px`, height: `${size}px` }">
      <svg
        :width="Math.round(size * 0.58)"
        :height="Math.round(size * 0.58)"
        viewBox="0 0 24 24"
        fill="none"
        aria-hidden="true"
      >
        <path
          d="M20 10c0 5.2-8 12-8 12S4 15.2 4 10a8 8 0 1 1 16 0Z"
          stroke="#fff"
          stroke-width="1.7"
          stroke-linejoin="round"
        />
        <circle cx="12" cy="10" r="2.5" fill="#fff" />
      </svg>
    </span>
    <span v-if="wordmark" class="logo-word">
      <span class="logo-name">你在哪</span>
      <span v-if="micro" class="logo-micro">FAMILY LOCATOR</span>
    </span>
  </span>
</template>

<style scoped>
.logo-lockup {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.logo-tile {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 9px;
  background: var(--blue);
}

.logo-word {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.logo-name {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.03em;
  line-height: 1.1;
  color: var(--ink);
}

.logo-micro {
  font-size: 8.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-muted);
  font-weight: 600;
}
</style>
