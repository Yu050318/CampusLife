// Read-only regression with internal API fixtures; never sends a purchase or publishes a blog.
async (page) => {
  const origin = page.url().startsWith('https://') ? new URL(page.url()).origin : 'http://127.0.0.1:5173'
  const calls = [], errors = []
  page.on('pageerror', error => errors.push(error.message))
  const shop = id => ({ id, typeId: id >= 200 ? 2 : 1, name: `内部回归店${id}`, images: '',
    address: '内部业务测试地址', score: 45, avgPrice: 30, sold: 10, comments: 0, openHours: '09:00-21:00' })
  await page.route(url => url.origin === 'http://localhost:8081' ||
    (url.origin === origin && url.pathname.startsWith('/internal-api/')), async route => {
    const request = route.request(), url = new URL(request.url())
    url.pathname = url.pathname.replace(/^\/internal-api/, '')
    if (!['GET', 'OPTIONS'].includes(request.method())) throw new Error('Regression must not mutate data')
    calls.push(url.pathname + url.search)
    let data = []
    if (url.pathname === '/shop-type/list') data = [{ id: 1, name: '原美食', sort: 1 }, { id: 2, name: '原购物', sort: 2 }]
    if (url.pathname === '/shop/of/type') {
      const start = Number(url.searchParams.get('typeId')) === 2 ? 201 : 101
      data = url.searchParams.get('current') === '2' ? [shop(start + 6)] : Array.from({ length: 6 }, (_, i) => shop(start + i))
    }
    if (url.pathname === '/shop/of/name') data = [shop(101)]
    if (/^\/shop\/\d+$/.test(url.pathname)) data = shop(Number(url.pathname.split('/').pop()))
    if (url.pathname.startsWith('/voucher/list/')) data = [{ id: 301, title: '内部业务测试券', payValue: 1000, actualValue: 2000, type: 0 }]
    await route.fulfill({ headers: { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*' },
      json: { success: true, data } })
  })
  await page.goto('about:blank')
  await page.goto(origin + '/#/pages/home/home')
  await page.getByText('搜一搜，发现身边好店', { exact: true }).click()
  await page.locator('input').fill('内部回归')
  await page.getByText('内部回归店101', { exact: true }).click()
  await page.getByText('内部业务测试券', { exact: true }).waitFor()
  await page.getByText('网友笔记', { exact: true }).waitFor()
  if (!calls.some(url => url.includes('/blog/of/shop?shopId=101'))) throw new Error('blog relation must retain internal shop ID')
  await page.getByText('查看更多', { exact: true }).click()
  await page.waitForURL(/voucher-list.*shopId=101/)

  await page.goto('about:blank')
  await page.goto(origin + '/#/pages/publish-blog/publish-blog')
  await page.getByText('关联店铺（可选）', { exact: true }).click()
  await page.getByText('内部回归店101', { exact: true }).waitFor()
  await page.getByText('选择', { exact: true }).first().click()
  await page.getByText('店铺评分', { exact: true }).waitFor()
  await page.getByText('内部回归店101', { exact: true }).waitFor()

  await page.goto('about:blank')
  await page.goto(origin + '/#/pages/home/home')
  await page.getByText('周边好店', { exact: true }).click()
  await page.waitForURL(/nearby-places/)
  await page.getByText('站内店铺 ↗', { exact: true }).click()
  await page.getByText('内部回归店101', { exact: true }).waitFor()
  await page.getByText('原购物', { exact: true }).click()
  await page.getByText('内部回归店201', { exact: true }).waitFor()
  await page.waitForTimeout(400) // Allow uni-app's route scroll restoration to finish.
  await page.getByText('内部回归店206', { exact: true }).scrollIntoViewIfNeeded()
  await page.mouse.wheel(0, 1000)
  await page.getByText('内部回归店207', { exact: true }).waitFor()
  const before = await page.locator('.shop-name').allTextContents()
  await page.locator('.nearby-places-entry').click()
  await page.waitForURL(/nearby-places/)
  await page.goBack()
  await page.getByText('内部回归店201', { exact: true }).waitFor()
  const after = await page.locator('.shop-name').allTextContents()
  if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('new page must preserve existing nearby list')
  await page.locator('.tb-item').filter({ hasText: '周边' }).click()
  await page.waitForURL(/nearby-places/)
  if (await page.locator('.tb-text.on').innerText() !== '周边') throw new Error('real places must highlight the nearby tab')
  await page.getByText('站内店铺 ↗', { exact: true }).click()
  await page.getByText('内部回归店201', { exact: true }).waitFor()
  await page.getByText('内部回归店201', { exact: true }).click()
  await page.waitForURL(/shop-detail.*id=201/)
  await page.getByText('内部业务测试券', { exact: true }).waitFor()
  if (errors.length) throw new Error(errors.join('; '))
  return { regression: 'passed', internalRequests: calls.length, data: 'fixtures', mutations: 0 }
}
