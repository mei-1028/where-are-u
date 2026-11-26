import { defineStore } from 'pinia'

export type ToastTone = 'default' | 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

interface UiState {
  toasts: ToastItem[]
  online: boolean
  networkReady: boolean
}

let toastId = 0

export const useUiStore = defineStore('ui', {
  state: (): UiState => ({
    toasts: [],
    online: true,
    networkReady: false,
  }),

  actions: {
    toast(message: string, tone: ToastTone = 'default', duration = 3400): void {
      const id = ++toastId
      this.toasts.push({ id, message, tone })
      window.setTimeout(() => this.dismiss(id), duration)
      if (this.toasts.length > 4) this.toasts.shift()
    },

    dismiss(id: number): void {
      this.toasts = this.toasts.filter((toast) => toast.id !== id)
    },

    initNetwork(): void {
      if (this.networkReady) return
      this.networkReady = true
      const sync = () => {
        this.online = navigator.onLine
      }
      sync()
      window.addEventListener('online', sync)
      window.addEventListener('offline', sync)
    },
  },
})
