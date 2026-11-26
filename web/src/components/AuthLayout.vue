<script setup lang="ts">
import LogoMark from './LogoMark.vue'

withDefaults(
  defineProps<{
    eyebrow: string
    title: string
    subtitle: string
    wide?: boolean
  }>(),
  { wide: false },
)
</script>

<template>
  <div class="auth-page">
    <div class="auth-shape auth-shape--teal" aria-hidden="true" />
    <div class="auth-shape auth-shape--coral" aria-hidden="true" />

    <header class="auth-top">
      <RouterLink to="/login" class="auth-brand" aria-label="你在哪">
        <LogoMark :size="32" wordmark micro />
      </RouterLink>
      <p class="micro auth-top__tag">PRIVATE BY DESIGN</p>
    </header>

    <main class="auth-center">
      <section class="auth-card" :class="{ 'auth-card--wide': wide }">
        <p class="micro">{{ eyebrow }}</p>
        <h1 class="auth-title">{{ title }}</h1>
        <p class="auth-subtitle">{{ subtitle }}</p>
        <slot />
      </section>

      <footer class="auth-footer">
        <span>v0.1.0 · M1</span>
        <span class="auth-footer__dot" aria-hidden="true" />
        <span>位置只属于家人</span>
      </footer>
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
  padding: 104px 24px 48px;
  overflow: hidden;
  background: var(--white);
}

.auth-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle at 1px 1px, #eceef2 1px, transparent 1.1px);
  background-size: 30px 30px;
  opacity: 0.55;
  pointer-events: none;
}

.auth-shape {
  position: absolute;
  pointer-events: none;
}

.auth-shape--teal {
  top: -210px;
  right: -140px;
  width: 520px;
  height: 520px;
  border-radius: 50%;
  background: var(--teal);
  opacity: 0.7;
  box-shadow: 0 0 0 28px rgba(195, 250, 245, 0.25);
}

.auth-shape--coral {
  bottom: -220px;
  left: -170px;
  width: 380px;
  height: 440px;
  border-radius: 64px;
  background: var(--coral);
  opacity: 0.6;
  transform: rotate(-14deg);
  box-shadow: 0 0 0 24px rgba(255, 198, 198, 0.18);
}

.auth-top {
  position: absolute;
  top: 26px;
  left: 30px;
  right: 30px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.auth-brand {
  display: inline-flex;
  border-radius: 10px;
}

.auth-top__tag {
  color: #9a9daa;
  font-size: 9px;
}

.auth-center {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  width: 100%;
}

.auth-card {
  width: min(430px, 100%);
  padding: 38px 36px 34px;
  border-radius: var(--r-panel-lg);
  background: rgba(255, 255, 255, 0.98);
  box-shadow:
    0 0 0 1px var(--ring),
    var(--float-shadow);
}

.auth-card--wide {
  width: min(468px, 100%);
}

.auth-title {
  margin-top: 12px;
  font-size: clamp(36px, 4.2vw, 48px);
  line-height: 1.12;
  letter-spacing: -0.032em;
  font-weight: 500;
}

.auth-subtitle {
  margin-top: 12px;
  color: var(--slate);
  font-size: 14.5px;
  line-height: 1.6;
}

.auth-footer {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--ink-muted);
  font-size: 10.5px;
  letter-spacing: 0.03em;
}

.auth-footer__dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #c7cad5;
}

@media (max-width: 600px) {
  .auth-page {
    justify-content: flex-start;
    padding: 86px 16px 32px;
  }

  .auth-top {
    top: 18px;
    left: 17px;
    right: 17px;
  }

  .auth-top__tag {
    display: none;
  }

  .auth-card {
    padding: 28px 22px 24px;
    border-radius: var(--r-panel);
  }

  .auth-shape--teal {
    width: 340px;
    height: 340px;
    top: -150px;
    right: -120px;
    opacity: 0.5;
  }

  .auth-shape--coral {
    width: 260px;
    height: 320px;
    bottom: -230px;
    left: -140px;
    opacity: 0.45;
  }

  .auth-footer {
    font-size: 10px;
  }
}
</style>
