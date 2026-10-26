// Feature-local query state; callers pass Vue's reactive state, never a global singleton.
export function createNearbyState() {
  return { items: [], page: 0, hasMore: false, loading: false, error: '',
    version: 0, conditions: null, pendingConditions: null, refreshFailed: false }
}

export function formatMeters(value) {
  if (value === null || value === undefined || !Number.isFinite(value) || value < 0) return '距离暂无'
  return value < 1000 ? `${Math.round(value)}m` : `${(value / 1000).toFixed(1)}km`
}

// uni-app H5 renders <button> as a custom element; supply native keyboard activation.
export function keyboardActivate(event) {
  if (event.key !== 'Enter' && event.key !== ' ') return
  event.preventDefault()
  // uni-app normalizes currentTarget to metadata; focused H5 buttons remain DOM elements.
  const element = typeof event.currentTarget?.click === 'function' ? event.currentTarget : document.activeElement
  if (element?.getAttribute('aria-disabled') !== 'true') element?.click()
}

export async function loadNearby(state, request, conditions, refresh = true) {
  if (!refresh && (state.loading || state.refreshFailed || !state.hasMore)) return false
  const version = refresh ? ++state.version : state.version
  const snapshot = refresh ? { ...conditions } : { ...state.conditions }
  const page = refresh ? 1 : state.page + 1
  state.loading = true
  state.error = ''
  state.pendingConditions = snapshot
  try {
    const result = await request({ ...snapshot, page })
    if (version !== state.version) return false
    if (!result.success || !result.data || !Array.isArray(result.data.items)) {
      throw new Error(result.errorMsg || '地点数据暂不可用，请重试')
    }
    const items = refresh ? [] : state.items.slice()
    const seen = new Set(items.map(item => item.poiId))
    for (const item of result.data.items) {
      if (item.poiId && !seen.has(item.poiId)) {
        seen.add(item.poiId)
        items.push(item)
      }
    }
    state.items = items
    state.page = page
    state.conditions = snapshot
    state.hasMore = Boolean(result.data.hasMore) && page < 5
    state.refreshFailed = false
    return true
  } catch (error) {
    if (version === state.version) {
      state.error = error?.message || error?.errorMsg || '查询失败，请稍后重试'
      if (refresh) state.refreshFailed = true
    }
    return false
  } finally {
    if (version === state.version) state.loading = false
  }
}
