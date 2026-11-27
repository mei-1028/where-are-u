<script setup lang="ts">
import LogoMark from './LogoMark.vue'

withDefaults(
  defineProps<{
    eyebrow?: string
    title: string
    subtitle?: string
    wide?: boolean
    brand?: boolean
  }>(),
  { wide: false, brand: true, eyebrow: '', subtitle: '' },
)
</script>

<template>
  <div class="auth-page">
    <div class="auth-shape auth-shape--teal" aria-hidden="true" />
    <div class="auth-shape auth-shape--coral" aria-hidden="true" />

    <header class="auth-top">
      <RouterLink to="/login" class="auth-brand" aria-label="你在哪">
        <LogoMark :size="26" wordmark micro />
      </RouterLink>
      <span class="auth-top__tag">自建服务器</span>
    </header>

    <main class="auth-center">
      <section class="auth-card" :class="{ 'auth-card--wide': wide }">
        <div v-if="brand" class="auth-card__brand">
          <span class="auth-card__mark">
            <svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M20 10c0 5.2-8 12-8 12S4 15.2 4 10a8 8 0 1 1 16 0Z" />
              <circle cx="12" cy="10" r="2.8" />
            </svg>
          </span>
          <span class="auth-card__wordmark">你在哪</span>
        </div>

        <p v-if="eyebrow" class="auth-eyebrow">{{ eyebrow }}</p>
        <h1 class="auth-title">{{ title }}</h1>
        <p v-if="subtitle" class="auth-subtitle">{{ subtitle }}</p>
        <slot />
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 96px 24px 48px;
  overflow: hidden;
  background: var(--white);
}

.auth-shape {
  position: absolute;
  pointer-events: none;
}

.auth-shape--teal {
  top: -150px;
  right: -110px;
  width: 420px;
  height: 420px;
  border-radius: 96px;
  background: var(--teal);
}

.auth-shape--coral {
  bottom: -110px;
  left: -110px;
  width: 300px;
  height: 300px;
  border-radius: 64px;
  background: var(--coral);
}

.auth-top {
  position: absolute;
  top: 30px;
  left: 36px;
  right: 36px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 12px;
}

.auth-brand {
  display: inline-flex;
  border-radius: 8px;
}

.auth-top__tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  background: var(--surface-soft);
  color: var(--ink-muted);
  font-size: 10px;
  line-height: 1;
}

.auth-center {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}

.auth-card {
  width: min(420px, 100%);
  padding: 36px 40px 36px;
  border-radius: var(--r-panel-lg);
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    0 24px 60px -40px rgba(28, 28, 30, 0.28);
}

.auth-card--wide {
  width: min(468px, 100%);
}

.auth-card__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.auth-card__mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--blue);
  color: var(--white);
}

.auth-card__wordmark {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.auth-eyebrow {
  margin-top: 24px;
  color: var(--ink-muted);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.auth-title {
  margin-top: 28px;
  font-size: 40px;
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 500;
}

.auth-eyebrow + .auth-title {
  margin-top: 10px;
}

.auth-subtitle {
  margin-top: 10px;
  color: var(--slate);
  font-size: 14px;
  line-height: 1.6;
}

@media (max-width: 600px) {
  .auth-page {
    justify-content: flex-start;
    padding: 86px 16px 32px;
  }

  .auth-top {
    top: 18px;
    left: 18px;
    right: 18px;
  }

  .auth-card {
    padding: 28px 22px 24px;
    border-radius: var(--r-panel);
  }

  .auth-title {
    font-size: 34px;
  }

  .auth-shape--teal {
    width: 300px;
    height: 300px;
    top: -140px;
    right: -110px;
    border-radius: 72px;
  }

  .auth-shape--coral {
    width: 220px;
    height: 220px;
    bottom: -120px;
    left: -100px;
    border-radius: 48px;
  }
}
</style>
