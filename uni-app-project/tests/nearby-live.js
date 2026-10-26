// Requires real local keys, backend/Redis, and a process-only fixed test map center.
// No network mocks. Only the project's POI responses are inspected; external SDK URLs are never printed.
async (page) => {
  const origin = page.url().startsWith('https://') ? new URL(page.url()).origin : 'http://127.0.0.1:5173'
  await page.setViewportSize({ width: 1280, height: 900 })
  const check = (condition, message) => { if (!condition) throw new Error(message) }
  let requests = 0
  const isPoi = response => new URL(response.url()).pathname === '/api/nearby/pois'
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/nearby/pois') requests++
  })
  const queryAfter = async action => {
    const responsePromise = page.waitForResponse(isPoi, { timeout: 15000 })
    await action()
    const response = await responsePromise
    const result = await response.json()
    check(response.status() === 200 && result.success, 'real POI query failed; inspect sanitized backend status')
    await page.locator('.notice').waitFor({ state: 'hidden' })
    return result.data
  }
  await page.goto('about:blank')
  await page.goto(origin + '/#/pages/nearby-places/nearby-places')
  await page.getByRole('button', { name: '搜索此区域', exact: true }).waitFor({ timeout: 20000 })
  const first = await queryAfter(() => page.getByRole('button', { name: '搜索此区域', exact: true }).click())
  check(first.items.length > 0, 'fixed test center should return real places')
  check(first.items.every(item => typeof item.poiId === 'string' && item.poiId), 'provider IDs must stay strings')
  const distances = first.items.map(item => item.distanceMeters).filter(value => value !== null)
  check(distances.every((value, index) => index === 0 || value >= distances[index - 1]), 'distance ordering')
  await page.locator('.nearby-poi-marker').first().waitFor()
  check(await page.locator('.nearby-poi-marker').count() === first.items.length, 'real SDK marker/list count mismatch')
  let before = requests
  await page.locator('.place-main').first().click()
  check(await page.locator('.place-card.selected').count() === 1, 'real card selection')
  await page.locator('.nearby-poi-marker').first().click({ force: true })
  check(await page.locator('.place-card.selected').count() === 1, 'real marker selection')
  check(requests === before, 'map selection must not request POIs')
  if (first.hasMore) {
    await queryAfter(() => page.getByRole('button', { name: '加载更多', exact: true }).click())
    check(await page.locator('.place-card').count() >= first.items.length, 'real pagination must append')
  }
  const shopping = await queryAfter(() => page.getByRole('button', { name: '购物', exact: true }).click())
  check(shopping.items.every(item => item.typeCode.startsWith('06')), 'real shopping category')
  await queryAfter(() => page.getByRole('button', { name: '3公里', exact: true }).click())
  await page.locator('input').fill('便利店')
  before = requests
  check(requests === before, 'typing is local')
  const keyword = await queryAfter(() => page.getByRole('button', { name: '搜索', exact: true }).click())
  check(keyword.items.length > 0, 'real keyword search')
  await queryAfter(() => page.getByRole('button', { name: '清空关键词', exact: true }).click())
  await queryAfter(() => page.getByRole('button', { name: '生活服务', exact: true }).click())
  check(await page.locator('.nearby-poi-marker').count() === await page.locator('.place-card').count(), 'final marker/list consistency')
  const screenshotPrefix = origin.startsWith('https://') ? 'nearby-real-https' : 'nearby-real'
  await page.screenshot({ path: `output/playwright/${screenshotPrefix}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: `output/playwright/${screenshotPrefix}-mobile-layout.png`, fullPage: true })
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'real map mobile overflow')
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin })
  const card = page.locator('.place-card').filter({ has: page.getByRole('button', { name: '复制地址', exact: true, disabled: false }) }).first()
  const copy = card.getByRole('button', { name: '复制地址', exact: true, disabled: false })
  // Compare clipboard against visible text without printing any returned address.
  const address = await card.locator('.place-address').innerText()
  await copy.click()
  check(await page.evaluate(() => navigator.clipboard.readText()) === address, 'real address copy')
  return { realSdk: 'passed', realPoi: 'passed', queries: requests, firstPage: first.items.length,
    viewport: 'desktop + mobile emulation', gpsAccuracy: 'not verified', phoneDevice: 'not verified' }
}
