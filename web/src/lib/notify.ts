import type { AlertInfo } from '@/api/types'

/**
 * Browser notifications only work while the page is open: there is no service
 * worker or push service (FCM is unreachable in mainland China). They are a
 * convenience on top of the in-app inbox, never the source of truth.
 */

export function notificationsSupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window
}

export function notificationPermission(): NotificationPermission {
  if (!notificationsSupported()) return 'denied'
  return Notification.permission
}

/** Must be called from a user gesture (a button click), not on page load. */
export async function requestNotificationPermission(): Promise<boolean> {
  if (!notificationsSupported()) return false
  if (Notification.permission === 'granted') return true
  if (Notification.permission === 'denied') return false
  try {
    return (await Notification.requestPermission()) === 'granted'
  } catch {
    return false
  }
}

export function showAlertNotification(alert: AlertInfo): void {
  if (!notificationsSupported() || Notification.permission !== 'granted') return
  if (document.visibilityState === 'visible') return
  try {
    const notification = new Notification(alert.title, {
      body: alert.body,
      tag: `wa-alert-${alert.id}`,
    })
    notification.onclick = () => {
      window.focus()
      window.dispatchEvent(
        new CustomEvent('wa:open-member', { detail: alert.target.userId }),
      )
      notification.close()
    }
  } catch {
    // Some browsers only allow notifications from a service worker; the
    // in-app badge still updates.
  }
}
