import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { pinia } from './stores'
import { useAuthStore } from './stores/auth'
import { useFamilyStore } from './stores/family'
import './styles/base.css'

const app = createApp(App)
app.use(pinia)
app.use(router)

window.addEventListener('wa:unauthorized', () => {
  const auth = useAuthStore(pinia)
  if (!auth.token) return
  auth.clearSession()
  useFamilyStore(pinia).reset()
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    void router.replace({
      name: 'login',
      query: { reason: 'expired', redirect: current.fullPath },
    })
  }
})

app.mount('#app')
