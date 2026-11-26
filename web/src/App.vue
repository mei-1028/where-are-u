<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import Toasts from '@/components/Toasts.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

onMounted(() => {
  ui.initNetwork()
})
</script>

<template>
  <RouterView v-slot="{ Component }">
    <component :is="Component" v-if="route.meta.bare" />
    <AppShell v-else>
      <component :is="Component" />
    </AppShell>
  </RouterView>
  <Toasts />
</template>
