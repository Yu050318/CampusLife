import { get } from '../utils/request.js'

export function getNearbyPlaces(params) {
  const base = import.meta.env.VITE_NEARBY_API_BASE || '/api'
  const url = new URL(`${base.replace(/\/$/, '')}/nearby/pois`, window.location.origin).href
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('查询超时，请重试')), 10000)
    // The existing wrapper owns transport/auth/error toasts. Late responses cannot commit page state.
    get(url, params).then(resolve, reject).finally(() => clearTimeout(timer))
  })
}
