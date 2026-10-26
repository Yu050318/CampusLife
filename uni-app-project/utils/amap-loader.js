let sdkPromise

export function loadAmap() {
  if (sdkPromise) return sdkPromise
  const key = import.meta.env.VITE_AMAP_JS_KEY
  if (!key) return Promise.reject(new Error('地图尚未配置，请设置 JS API Key'))
  sdkPromise = new Promise((resolve, reject) => {
    window._AMapSecurityConfig = {
      serviceHost: new URL('/_AMapService', window.location.origin).href
    }
    const script = document.createElement('script')
    const callback = `__campusAmapReady_${Date.now()}`
    let timer
    const fail = () => {
      clearTimeout(timer)
      delete window[callback]
      script.remove()
      sdkPromise = undefined
      reject(new Error('地图加载失败，请检查网络后重试'))
    }
    window[callback] = () => {
      clearTimeout(timer)
      delete window[callback]
      if (!window.AMap) return fail()
      resolve(window.AMap)
    }
    script.onerror = fail
    timer = setTimeout(fail, 15000)
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&callback=${callback}`
    document.head.appendChild(script)
  })
  return sdkPromise
}

export function configuredCampusCenter() {
  const value = import.meta.env.VITE_NEARBY_CAMPUS_CENTER || ''
  const parts = value.split(',')
  if (parts.length !== 2 || parts.some(part => !part.trim())) return null
  const [longitude, latitude] = parts.map(Number)
  return Number.isFinite(longitude) && Number.isFinite(latitude) && Math.abs(longitude) <= 180
    && Math.abs(latitude) <= 90 ? { longitude, latitude } : null
}
