<script setup lang="ts">
import { RouterLink } from 'vue-router'
import Icon from '@/components/Icon.vue'
</script>

<template>
  <div class="page not-found">
    <div class="not-found__art" aria-hidden="true">
      <span class="not-found__orb accent-teal" />
      <span class="not-found__orb not-found__orb--small accent-coral" />
      <span class="not-found__nine">404</span>
    </div>
    <p class="micro">404 · NOT FOUND</p>
    <h1 class="not-found__title">这张地图上没有这一页</h1>
    <p class="not-found__copy">
      链接可能已经失效，或者你访问的地址不在当前版本里。回到家庭地图看看家人吧。
    </p>
    <div class="not-found__actions">
      <RouterLink to="/" class="btn btn--primary">
        <Icon name="map" :size="16" />
        回到家庭地图
      </RouterLink>
      <RouterLink to="/settings" class="btn">前往设置</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  min-height: calc(100dvh - var(--topbar-h));
  max-width: 760px;
  padding-top: 40px;
  padding-bottom: 80px;
}

.not-found__art {
  position: relative;
  width: 100%;
  margin-bottom: 28px;
  min-height: 90px;
}

.not-found__nine {
  font-size: clamp(80px, 12vw, 140px);
  line-height: 0.9;
  letter-spacing: -0.06em;
  font-weight: 500;
  color: var(--surface-sunken);
}

.not-found__orb {
  position: absolute;
  top: -20px;
  left: 190px;
  width: 64px;
  height: 64px;
  border-radius: 50%;
}

.not-found__orb--small {
  top: 54px;
  left: 260px;
  width: 28px;
  height: 28px;
}

.not-found__title {
  margin-top: 10px;
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 500;
}

.not-found__copy {
  max-width: 480px;
  margin-top: 14px;
  color: var(--slate);
  font-size: 15px;
  line-height: 1.7;
}

.not-found__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 26px;
}

@media (max-width: 600px) {
  .not-found {
    padding-top: 24px;
  }

  .not-found__orb {
    left: 150px;
  }

  .not-found__orb--small {
    left: 210px;
  }

  .not-found__actions .btn {
    flex: 1 1 auto;
  }
}
</style>
