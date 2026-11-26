declare global {
  interface Window {
    AMap?: any
    _AMapSecurityConfig?: { securityJsCode?: string }
  }
}

export const AMAP_KEY = String(import.meta.env.VITE_AMAP_KEY ?? '').trim()
export const AMAP_SECURITY_CODE = String(
  import.meta.env.VITE_AMAP_SECURITY_CODE ?? '',
).trim()

export const isAmapConfigured = Boolean(AMAP_KEY && AMAP_SECURITY_CODE)

let loadPromise: Promise<any> | null = null

/**
 * Loads AMap JS API 2.0 on demand. Keys only ever come from Vite env vars.
 * `securityJsCode` MUST be set on window before the script is initialised,
 * which is why it is assigned here rather than at map construction time.
 */
export function loadAMap(): Promise<any> {
  if (!isAmapConfigured) {
    return Promise.reject(new Error('未配置高德地图 Key'))
  }
  if (window.AMap) return Promise.resolve(window.AMap)
  if (loadPromise) return loadPromise

  loadPromise = new Promise((resolve, reject) => {
    window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_CODE }

    const script = document.createElement('script')
    script.type = 'text/javascript'
    script.async = true
    script.dataset.waAmap = 'true'
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(AMAP_KEY)}`

    const timeout = window.setTimeout(() => {
      reject(new Error('高德地图脚本加载超时'))
    }, 15_000)

    script.onload = () => {
      window.clearTimeout(timeout)
      if (window.AMap) resolve(window.AMap)
      else reject(new Error('高德地图脚本已加载但未初始化'))
    }

    script.onerror = () => {
      window.clearTimeout(timeout)
      reject(new Error('高德地图脚本加载失败'))
    }

    document.head.appendChild(script)
  })

  return loadPromise
}
