function point(value) {
  const longitude = value?.lng, latitude = value?.lat
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude) || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) {
    throw new Error('定位坐标转换失败，可在地图上选择搜索区域')
  }
  return { longitude, latitude }
}

export function getBrowserPosition(geolocation) {
  return new Promise((resolve, reject) => {
    const fail = () => reject(new Error('无法获取当前位置，可在地图上选择搜索区域'))
    if (!geolocation?.getCurrentPosition) return fail()
    try {
      geolocation.getCurrentPosition(result => {
        try { resolve(point({ lng: result?.coords?.longitude, lat: result?.coords?.latitude })) }
        catch { fail() }
      }, fail, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 })
    } catch { fail() }
  })
}

// Accept only the WGS84 point acquired above; POI/SDK GCJ-02 points never enter here.
export function convertGpsToAmap(amap, gps) {
  const original = point({ lng: gps.longitude, lat: gps.latitude })
  return new Promise((resolve, reject) => {
    const fail = () => reject(new Error('定位坐标转换失败，可在地图上选择搜索区域'))
    const timer = setTimeout(fail, 5000)
    try {
      amap.convertFrom([original.longitude, original.latitude], 'gps', (status, converted) => {
        clearTimeout(timer)
        if (status !== 'complete') return fail()
        try { resolve(point(converted?.locations?.[0])) } catch { fail() }
      })
    } catch { clearTimeout(timer); fail() }
  })
}
