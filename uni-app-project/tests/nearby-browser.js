// Run with playwright-cli run-code --filename uni-app-project/tests/nearby-browser.js.
// Only external boundaries are stubbed: no test hooks or mock data are added to product code.
async (page) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const calls = []
  let fail = false, failPageTwo = false, delayFood = false, initiallyDenied = false, locationDelay = 30
  const check = (condition, message) => { if (!condition) throw new Error(message) }
  await page.route('https://nearby-images.test/**', async route => {
    if (route.request().url().includes('broken')) return route.fulfill({ status: 404, body: '' })
    return route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="104"><rect width="96" height="104" fill="#c9dac6"/></svg>' })
  })
  await page.route(/^https:\/\/webapi\.amap\.com\/maps\?/, async route => {
    const callback = new URL(route.request().url()).searchParams.get('callback')
    await route.fulfill({ contentType: 'application/javascript', body: `
      (() => {
        window.__amapTest = { moves: 0, fits: 0, destroyed: 0, conversions: 0, nativeCalls:0 };
        window.__geoDenied = ${initiallyDenied};
        const point = p => ({ lng: p[0], lat: p[1] });
        class Map {
          constructor(host, options) {
            if (!(host instanceof HTMLDivElement)) throw new Error('Map container must be a native div');
            this.host = host; this.center = point(options.center); this.events = {};
            host.style.background = 'repeating-linear-gradient(0deg,transparent 0 64px,#ffffffb0 64px 70px),repeating-linear-gradient(90deg,#e1e9df 0 80px,#ffffffb0 80px 87px)';
            const watermark = document.createElement('span'); watermark.textContent = '受控测试地图 · 非真实地理数据';
            watermark.style.cssText = 'position:absolute;bottom:6px;left:8px;color:#52694c;font-size:10px'; host.append(watermark);
            host.addEventListener('pointerup', event => { if(event.target !== host) return; this.center = point([113.36,23.16]); this.events.dragend?.(); });
          }
          on(name, handler) { this.events[name] = handler; }
          getCenter() { return this.center; }
          setCenter(p) { this.center = p.lng === undefined ? point(p) : p; window.__amapTest.moves++; }
          setZoomAndCenter(z, p) { this.setCenter(p); }
          setFitView() { window.__amapTest.fits++; }
          add(overlay) {
            if (overlay.element) { overlay.element.style.position='absolute'; overlay.element.style.left=(30+this.host.querySelectorAll('[data-poi]').length*62)+'px';overlay.element.style.top='70px';this.host.append(overlay.element); }
          }
          remove(overlay) { overlay.element?.remove(); }
          destroy() { this.host.replaceChildren();window.__amapTest.destroyed++; }
        }
        class Marker {
          constructor(options) { this.element=options.content;this.element.dataset.poi=options.title;this.position=point(options.position);
            if(options.title==='当前位置'){window.__amapTest.userCenter=options.position;window.__amapTest.userZIndex=options.zIndex;} }
          on(name, fn) { if(name==='click') {this.listener=fn;this.element.addEventListener('click',fn);} }
          off() { this.element.removeEventListener('click',this.listener); }
          getContent() { return this.element; }
          getPosition() { return this.position; }
          setZIndex(value) { this.element.style.zIndex=value; }
        }
        class Overlay { constructor(options) {this.options=options;} }
        Object.defineProperty(navigator,'geolocation',{configurable:true,value:{getCurrentPosition(success,failure,options){
          if(!options.enableHighAccuracy || options.maximumAge!==0) throw Error('Native location must request fresh high-accuracy data');
          window.__amapTest.nativeCalls++;
          setTimeout(()=>window.__geoDenied?failure({code:1}):success({coords:{longitude:113.345,latitude:23.152,accuracy:10}}),${locationDelay});
        }}});
        window.AMap = { Map, Marker, Circle:Overlay, Pixel:class {},
          convertFrom(input,type,callback) { window.__amapTest.conversions++;setTimeout(()=>callback('complete',{locations:[point([113.35,23.15])]}),20); } };
        window[${JSON.stringify(callback)}]();
      })();` })
  })
  await page.route('**/api/nearby/pois?*', async route => {
    const params = Object.fromEntries(new URL(route.request().url()).searchParams)
    calls.push(params)
    if (delayFood && params.category === 'food') await new Promise(resolve => setTimeout(resolve, 500))
    if (fail || (failPageTwo && params.page === '2')) {
      await route.fulfill({ json: { success: false, errorMsg: '测试服务暂不可用' } })
      return
    }
    const prefix = { food: '校园咖啡', shopping: '书店', life: '生活服务' }[params.category]
    const place = (id, name, distance) => ({ poiId: id, name, typeName: '受控测试地点', typeCode: '050000',
      address: '校园路 18 号', longitude: 113.35, latitude: 23.15, distanceMeters: distance,
      rating: id.endsWith('1') ? 4.8 : null,
      photoUrl: id.endsWith('1') ? 'https://nearby-images.test/cover.svg' : 'https://nearby-images.test/broken.jpg' })
    const items = params.keyword === '不存在' ? [] : params.page === '2'
      ? [place(params.category + '1', prefix + '一号店', 320), place(params.category + '3', prefix + '三号店', 800)]
      : [place(params.category + '1', prefix + '一号店', 320), place(params.category + '2', prefix + '二号店', 1500)]
    await route.fulfill({ json: { success: true, data: { items, page: Number(params.page), pageSize: 20,
      hasMore: params.page === '1' && items.length > 0 } } })
  })
  const origin = page.url().startsWith('https://') ? new URL(page.url()).origin : 'http://127.0.0.1:5173'
  await page.goto('about:blank')
  await page.goto(origin + '/#/pages/nearby-places/nearby-places')
  await page.getByText('校园咖啡一号店', { exact: true }).waitFor()
  check(calls.length === 1 && calls[0].radius === '1000', 'initial location must query exactly once')
  check(calls[0].longitude === '113.35', 'POI search must use corrected coordinates, not raw HTML5 GPS')
  check(await page.evaluate(() => window.__amapTest.conversions === 1 && window.__amapTest.nativeCalls === 1), 'native WGS84 must be acquired and converted exactly once')
  check(await page.evaluate(() => window.__amapTest.userCenter[0] === 113.35 && window.__amapTest.userCenter[1] === 23.15), 'blue point and POI query must use the same GCJ-02 center')
  check(await page.evaluate(() => window.__amapTest.userZIndex > 200), 'current location must remain above selected POI markers')
  check(await page.getByText('320m', { exact: true }).count() === 1, 'meter formatting')
  check(await page.getByText('1.5km', { exact: true }).count() === 1, 'kilometer formatting')
  await page.getByText('4.8分', { exact: true }).waitFor()
  await page.getByText('暂无评分', { exact: true }).waitFor()
  await page.locator('.place-card').nth(1).scrollIntoViewIfNeeded()
  await page.getByText('暂无图片', { exact: true }).waitFor()
  check(await page.locator('.place-photo').count() === 1, 'failed images must fall back while valid photos remain')

  let count = calls.length
  await page.locator('[data-poi="校园咖啡二号店"]').click()
  check(await page.locator('.place-card.selected').innerText().then(t => t.includes('校园咖啡二号店')), 'marker must select card')
  await page.getByText('地图定位', { exact: true }).first().click()
  check(calls.length === count, 'programmatic map movement must not query')
  check(await page.getByText('搜索此区域', { exact: true }).count() === 0, 'programmatic movement must not show area action')

  failPageTwo = true
  await page.getByText('加载更多', { exact: true }).click()
  await page.getByText('测试服务暂不可用', { exact: true }).waitFor()
  failPageTwo = false
  await page.getByText('重试查询', { exact: true }).click()
  await page.getByText('校园咖啡三号店', { exact: true }).waitFor()
  check(calls.slice(-2).every(p => p.page === '2'), 'pagination retry must not skip')
  check(await page.locator('.place-card').count() === 3, 'deduplicate page boundary')

  fail = true
  await page.getByText('3公里', { exact: true }).click()
  await page.getByText(/更新失败，当前仍为上次结果/).waitFor()
  check(await page.locator('.place-card').count() === 3, 'refresh failure must retain cards')
  fail = false
  await page.getByText('重试查询', { exact: true }).click()
  await page.locator('.notice.error').waitFor({ state: 'hidden' })
  await page.getByText('购物', { exact: true }).click()
  await page.getByText('书店一号店', { exact: true }).waitFor()
  delayFood = true
  await page.getByText('餐饮', { exact: true }).click()
  await page.getByText('生活服务', { exact: true }).first().click()
  await page.getByText('生活服务一号店', { exact: true }).waitFor()
  await page.waitForTimeout(650)
  check(await page.getByText('校园咖啡一号店', { exact: true }).count() === 0, 'late category response must be ignored')
  delayFood = false

  const search = page.locator('input')
  count = calls.length
  await search.fill('不存在')
  check(calls.length === count, 'typing alone must not query')
  await page.getByRole('button', { name: '搜索', exact: true }).focus()
  await page.keyboard.press('Enter')
  await page.getByText('这个范围暂未找到相关地点', { exact: true }).waitFor()
  await page.getByText('清空', { exact: true }).click()
  await page.getByText('生活服务一号店', { exact: true }).waitFor()

  // Pointer action exercises the SDK's user drag boundary, unlike setCenter.
  count = calls.length
  await page.locator('.map-canvas').click({ position: { x: 220, y: 170 } })
  await page.getByText('搜索此区域', { exact: true }).waitFor()
  check(calls.length === count, 'drag does not query before confirmation')
  await page.getByText('搜索此区域', { exact: true }).click()
  await page.locator('.notice').waitFor({ state: 'hidden' })
  check(calls.at(-1).longitude === '113.36', 'area center is sent')

  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin })
  await page.getByText('复制地址', { exact: true }).first().click()
  check(await page.evaluate(() => navigator.clipboard.readText()) === '校园路 18 号', 'copy address')
  await page.evaluate(() => { window.__geoDenied = true })
  await page.getByText('重新定位', { exact: true }).click()
  await page.getByText('无法获取当前位置，可在地图上选择搜索区域', { exact: true }).waitFor()
  check(await page.locator('.place-card').count() === 2, 'location rejection retains results')

  await page.getByText('地址已复制', { exact: true }).waitFor({ state: 'hidden' })
  await page.getByText('地图定位', { exact: true }).first().click()
  await page.screenshot({ path: 'output/playwright/nearby-desktop-stub.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'output/playwright/nearby-mobile-stub.png', fullPage: true })
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'mobile must not overflow')
  await page.reload()
  await page.getByText('校园咖啡一号店', { exact: true }).waitFor()
  // Reload clears the SDK fixture's denied flag. Now verify a denied first location independently.
  initiallyDenied = true
  count = calls.length
  await page.reload()
  await page.getByText('无法获取当前位置，可在地图上选择搜索区域', { exact: true }).waitFor()
  check(calls.length === count, 'denied first location must not query an invented center')
  await page.locator('.map-canvas').click({ position: { x: 200, y: 160 } })
  await page.getByText('搜索此区域', { exact: true }).click()
  await page.getByText('校园咖啡一号店', { exact: true }).waitFor()
  // A delayed first location must not undo a newer manual choice or pan the map.
  initiallyDenied = false
  locationDelay = 1200
  await page.reload()
  await page.getByText('定位中…', { exact: true }).waitFor()
  await page.locator('.map-canvas').click({ position: { x: 200, y: 160 } })
  await page.getByText('搜索此区域', { exact: true }).click()
  await page.getByText('校园咖啡一号店', { exact: true }).waitFor()
  count = calls.length
  const moves = await page.evaluate(() => window.__amapTest.moves)
  await page.waitForTimeout(1400)
  check(calls.length === count, 'late initial location must not query after manual selection')
  check(await page.evaluate(() => window.__amapTest.moves) === moves, 'late initial location must not pan the map')
  await page.getByText('以地图选点搜索', { exact: true }).waitFor()
  await page.getByRole('button', { name: '重新定位', exact: true }).click()
  await page.getByText('以当前位置搜索', { exact: true }).waitFor()
  check(calls.length === count + 1 && calls.at(-1).longitude === '113.35', 'explicit relocation must still work')
  check(errors.length === 0, 'browser runtime errors: ' + errors.join('; '))
  return { browserChecks: 'passed', queries: calls.length,
    viewport: 'desktop + 390px mobile emulation', externalServices: 'stubbed' }
}
