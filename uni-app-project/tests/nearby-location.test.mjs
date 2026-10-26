import { test } from 'node:test'
import assert from 'node:assert/strict'
import { getBrowserPosition, convertGpsToAmap } from '../utils/nearby-location.js'

test('native location requests fresh high-accuracy WGS84 coordinates once', async () => {
  let calls = 0
  const gps = await getBrowserPosition({ getCurrentPosition(success, failure, options) {
    calls++
    assert.deepEqual(options, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 })
    success({ coords: { longitude: 113.35, latitude: 23.15 } })
  }, watchPosition() { throw Error('Must not track position') } })
  assert.deepEqual(gps, { longitude: 113.35, latitude: 23.15 })
  assert.equal(calls, 1)
})

test('native WGS84 point is converted exactly once without interpreting SDK flags', async () => {
  let conversions = 0
  const gps = await getBrowserPosition({ getCurrentPosition(success) {
    success({ coords: { longitude: 113.35, latitude: 23.15 } })
  } })
  const result = await convertGpsToAmap({ convertFrom(input, type, callback) {
    conversions++
    assert.deepEqual(input, [113.35, 23.15])
    assert.equal(type, 'gps')
    callback('complete', { locations: [{ lng: 113.355, lat: 23.147 }] })
  } }, gps)
  assert.deepEqual(result, { longitude: 113.355, latitude: 23.147 })
  assert.equal(conversions, 1)
})

test('unavailable, denied, and invalid native positions fail rather than use IP estimates', async () => {
  for (const geolocation of [null, { getCurrentPosition(success, failure) { failure({ code: 1 }) } },
    { getCurrentPosition(success) { success({ coords: { longitude: NaN, latitude: 23 } }) } }]) {
    await assert.rejects(getBrowserPosition(geolocation), /无法获取当前位置/)
  }
})

test('conversion failure must not return the raw GPS point', async () => {
  for (const callbackResult of [['error', {}], ['complete', { locations: [{ lng: NaN, lat: 23 }] }]]) {
    await assert.rejects(convertGpsToAmap({ convertFrom(input, type, callback) { callback(...callbackResult) } },
      { longitude: 113, latitude: 23 }), /坐标转换失败/)
  }
})
