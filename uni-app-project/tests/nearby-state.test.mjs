import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createNearbyState, loadNearby, formatMeters, keyboardActivate } from '../utils/nearby-state.js'

const conditions = { longitude: 113, latitude: 23, category: 'food', radius: 1000, keyword: '' }
const response = (ids, hasMore = false) => ({ success: true, data: { items: ids.map(poiId => ({ poiId })), hasMore } })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('late result and finally cannot overwrite a newer query', async () => {
  const state = createNearbyState(), old = deferred(), latest = deferred()
  const first = loadNearby(state, () => old.promise, conditions)
  const second = loadNearby(state, () => latest.promise, { ...conditions, category: 'life' })
  old.resolve(response(['old']))
  assert.equal(await first, false)
  assert.equal(state.loading, true)
  latest.resolve(response(['new']))
  assert.equal(await second, true)
  assert.deepEqual(state.items.map(item => item.poiId), ['new'])
  assert.equal(state.conditions.category, 'life')
})

test('refresh failure retains committed results and blocks pagination', async () => {
  const state = createNearbyState()
  await loadNearby(state, async () => response(['old'], true), conditions)
  await loadNearby(state, async () => { throw new Error('timeout') }, { ...conditions, radius: 3000 })
  assert.equal(state.conditions.radius, 1000)
  assert.equal(state.pendingConditions.radius, 3000)
  assert.equal(state.items[0].poiId, 'old')
  assert.equal(state.refreshFailed, true)
  assert.equal(await loadNearby(state, () => { throw new Error('must not request') }, conditions, false), false)
})

test('failed pagination retries the same page, preserves snapshot, and deduplicates', async () => {
  const state = createNearbyState(), pages = []
  await loadNearby(state, async () => response(['a'], true), conditions)
  await loadNearby(state, async p => { pages.push(p.page); throw new Error('offline') }, conditions, false)
  assert.equal(state.page, 1)
  await loadNearby(state, async p => {
    pages.push(p.page)
    assert.equal(p.category, 'food')
    return response(['a', 'b', 'b'])
  }, { ...conditions, category: 'life' }, false)
  assert.deepEqual(pages, [2, 2])
  assert.deepEqual(state.items.map(item => item.poiId), ['a', 'b'])
})

test('invalidated response does not commit after leaving the page', async () => {
  const state = createNearbyState(), pending = deferred()
  const load = loadNearby(state, () => pending.promise, conditions)
  state.version++
  state.loading = false
  pending.resolve(response(['late']))
  assert.equal(await load, false)
  assert.deepEqual(state.items, [])
})

test('business failures and the fifth-page boundary are respected', async () => {
  const state = createNearbyState()
  await loadNearby(state, async () => ({ success: false, errorMsg: 'quota' }), conditions)
  assert.equal(state.error, 'quota')
  await loadNearby(state, async () => response(['a'], true), conditions)
  state.page = 4
  await loadNearby(state, async () => response(['b'], true), conditions, false)
  assert.equal(state.page, 5)
  assert.equal(state.hasMore, false)
})

test('meters never use the existing kilometer formatter', () => {
  assert.equal(formatMeters(320), '320m')
  assert.equal(formatMeters(1500), '1.5km')
  assert.equal(formatMeters(0), '0m')
  for (const value of [null, undefined, NaN, -1]) assert.equal(formatMeters(value), '距离暂无')
})

test('custom H5 buttons activate on keyboard and respect disabled state', () => {
  let clicks = 0
  const event = { key: 'Enter', preventDefault() {}, currentTarget: {
    getAttribute: () => 'false', click: () => clicks++ } }
  keyboardActivate(event)
  assert.equal(clicks, 1)
  event.currentTarget.getAttribute = () => 'true'
  keyboardActivate(event)
  assert.equal(clicks, 1)
})
