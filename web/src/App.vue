<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import Toasts from '@/components/Toasts.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()

function onOpenMember(event: Event): void {
  const userId = (event as CustomEvent<unknown>).detail
  if (typeof userId === 'string' && userId) {
    void router.push({ name: 'map', query: { member: userId } })
  }
}

onMounted(() => {
  ui.initNetwork()
  window.addEventListener('wa:open-member', onOpenMember)
})

onBeforeUnmount(() => {
  window.removeEventListener('wa:open-member', onOpenMember)
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
