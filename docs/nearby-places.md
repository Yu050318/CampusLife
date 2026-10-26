# 真实周边地点模块

复用 CampusLife 的 Vue Options API、请求工具、导航和 `Result` 返回协议，以独立模块接入高德地图。H5 首页“周边好店”和底部“周边”打开真实地点页；“站内店铺”入口连接原店铺业务。

## 模块组成

| 模块 | 职责 |
| --- | --- |
| `NearbyController` | 参数校验、限流、HTTP 状态与统一返回 |
| `AmapNearbyService` | 高德 v5 周边查询、分类映射、超时与数据清洗 |
| `NearbyRateLimiter` | Redis Lua 原子计数、窗口过期、可信代理 IP |
| `api/nearby.js` | 复用 `get()`，组合接口参数与等待上限 |
| `nearby-state.js` | 查询快照、版本、分页提交与去重 |
| `nearby-location.js` | 浏览器原生定位及官方坐标转换 |
| `nearby-map.vue` | SDK 生命周期、蓝点、范围、标记与选中事件 |
| `nearby-place-card.vue` | 图片、评分、名称、分类、地址、距离与复制 |

## 配置

从示例复制本地文件，已存在时保留：

```powershell
if (!(Test-Path .env.local)) { Copy-Item .env.example .env.local }
if (!(Test-Path uni-app-project/.env.local)) {
    Copy-Item uni-app-project/.env.example uni-app-project/.env.local
}
```

| 配置 | 读取方 | 作用 |
| --- | --- | --- |
| `AMAP_WEB_KEY` | 后端 | 高德 Web 服务 Key |
| `NEARBY_RATE_LIMIT` | 后端 | 每 IP 每 60 秒阈值，默认 30 |
| `NEARBY_TRUSTED_PROXIES` | 后端 | 可信代理精确 IP，逗号分隔 |
| `VITE_AMAP_JS_KEY` | H5 | 高德 Web 端 JS API Key |
| `AMAP_JS_SECURITY_CODE` | 开发代理 | JS 安全密钥，仅服务端读取 |
| `VITE_NEARBY_API_BASE` | H5 | 默认 `/api`，使用同源代理 |
| `NEARBY_BACKEND_URL` | 开发代理 | 默认 `http://127.0.0.1:8081` |
| `VITE_NEARBY_CAMPUS_CENTER` | H5 | 可选，人工确认的 GCJ-02 经度,纬度 |

JS API Key 与 Web 服务 Key 按各自平台分别申请。JS 安全密钥保持非 `VITE_` 前缀。校园中心为空时显示全国范围，用户可通过定位或地图选区确定中心。

Spring Boot 通过进程环境读取根目录配置，在根目录 PowerShell 中执行：

```powershell
Get-Content .env.local | ForEach-Object {
    if ($_ -match '^\s*(AMAP_WEB_KEY|NEARBY_RATE_LIMIT|NEARBY_TRUSTED_PROXIES)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
    }
}
mvn spring-boot:run
```

示例值采用无引号、无行尾注释的格式。数据库、Redis 和 OSS 等沿用项目原配置。另开终端启动 H5：

```powershell
Set-Location uni-app-project
npm ci
npm run dev:h5
```

## 查询接口

**`GET /nearby/pois`**，H5 默认经 `/api/nearby/pois` 转发，返回项目 `Result`。

| 参数 | 规则 |
| --- | --- |
| `longitude`、`latitude` | 必填，合法有限经纬度，GCJ-02 |
| `category` | `food` / `shopping` / `life`，默认 `food` |
| `radius` | 1000 / 3000 / 5000 米，默认 1000 |
| `keyword` | 可选，去除首尾空白后最多 80 个 Unicode 字符 |
| `page` | 1–5，默认 1，每页 20 条 |

`data` 包含 `items`、`page`、`pageSize`、`hasMore`。每条地点包含：

| 字段 | 含义 |
| --- | --- |
| `poiId` | 字符串 POI ID |
| `name`、`typeName`、`typeCode` | 名称及高德分类 |
| `address` | 地址，空值展示“地址暂无” |
| `longitude`、`latitude` | 地点 GCJ-02 坐标 |
| `distanceMeters` | 距查询中心的米制距离，缺失为 null |
| `rating` | 0–5 的有限评分，缺失或非法为 null |
| `photoUrl` | 首个可用 HTTPS 图片链接，缺失为空字符串 |

固定分类由后端映射为高德分类码。查询使用距离排序和六位小数坐标，以原始返回条数判断后续页，最多加载 5 页；前端按 POI ID 去重。

图片与评分来自同一次 v5 周边查询的 `show_fields=business,photos`。图片由浏览器加载，失败或缺失时回退占位；缺评分时显示“暂无评分”。原 HTTP 图片链接升级为 HTTPS，并过滤非法 URL。POI 与内部店铺独立，内部详情、笔记和优惠券继续使用内部 `shopId`。

| HTTP 状态 | 约定 |
| --- | --- |
| 200 | 查询成功，允许空结果 |
| 400 | 参数校验失败 |
| 429 | 请求达到限流阈值 |
| 502 | 上游调用或响应异常 |
| 503 | 配置或限流依赖暂不可用 |

## 定位与交互

定位链路为 **浏览器原生 WGS84 → 高德 `convertFrom(..., 'gps')` 一次转换 → GCJ-02**。原生请求启用高精度、`maximumAge:0`，等待上限 15 秒。蓝点和周边查询共用转换结果，高德 POI 的坐标直接展示。

- 初次定位成功查询 1 公里餐饮；定位失败时可通过地图选区搜索。
- 分类、半径变化重新查询；关键词提交后查询，输入过程保持当前结果。
- 拖动地图展示“搜索此区域”，由用户确认更新。
- 点击卡片居中标记，点击标记高亮并滚动到对应卡片。
- 新查询使用条件快照与版本号；旧响应、旧定位回调及离页后的结果失效。
- 刷新失败保留旧结果与条件说明；分页成功后推进页码，失败重试原页。

## HTTPS 与代理

[部署模板](nearby-nginx.conf.template)提供新查询接口与 SDK 安全代理，合入现有 HTTPS server，并在 http 块配置共享限流区。

固定 SDK 代理路径为：

- `/v3/ip`
- `/v3/geocode/regeo`
- `/v3/assistant/coordinate/convert`

模板校验 JS Key，在服务端注入安全密钥，保留 SDK 平台参数并归一化转换请求中的 Key。转发移除 Cookie/Authorization，IP 头由可信代理覆盖。应用日志只记录错误分类、耗时与数量。

将模板中的 `${AMAP_JS_KEY}`、`${AMAP_JS_SECURITY_CODE}` 替换为部署环境值；使用 envsubst 时指定替换列表，以保留 Nginx 的 `$arg_*`、`$remote_addr` 等变量。配置后执行 `nginx -t`。

开发代理只匹配 `/api/nearby/pois`，保持项目 `api/` 源码目录正常加载。站内业务沿用现有接口地址配置，部署时统一配置对应反向代理。

## 测试与构建

```powershell
# 根目录：8 项后端模块测试
mvn "-Dtest=com.cn.nearby.NearbyTest" test

# 前端目录：11 项状态与坐标测试
Set-Location uni-app-project
node --test tests/nearby-state.test.mjs tests/nearby-location.test.mjs

$env:UNI_INPUT_DIR = (Get-Location).Path
node node_modules/@dcloudio/vite-plugin-uni/bin/uni.js build -p h5
```

真实 Redis 集成测试连接独立测试实例，默认本机 6379，可用 `NEARBY_TEST_REDIS_PORT` 调整。测试使用随机键并清理，验证 30 次允许、第 31 次限制及 60 秒窗口自然到期：

```powershell
$env:NEARBY_REDIS_TEST = 'true'
mvn "-Dtest=com.cn.nearby.NearbyRedisIntegrationTest" test
Remove-Item Env:NEARBY_REDIS_TEST
```

浏览器脚本使用外部边界测试数据，在根目录运行，H5 开发服务需先启动：

```powershell
npx --yes --package @playwright/cli playwright-cli -s=campus-nearby open http://127.0.0.1:5173/ --browser chrome
npx --yes --package @playwright/cli playwright-cli -s=campus-nearby run-code --filename uni-app-project/tests/nearby-browser.js
npx --yes --package @playwright/cli playwright-cli -s=campus-nearby run-code --filename uni-app-project/tests/nearby-regression.js
```

`nearby-live.js` 用于真实数据联调：填写密钥、启动服务，并在测试进程配置固定测试选点 `VITE_NEARBY_CAMPUS_CENTER=113.357619,23.154708` 后运行。该坐标仅为测试区域，脚本约调用 7 次 POI 查询。

## 接入文件

| 文件 | 接入内容 |
| --- | --- |
| `MvcConfig.java` | 放行独立只读查询接口 |
| `pages.json` | 注册独立地点页面 |
| `pages/nearby/nearby.vue` | 提供真实地点入口，保留原业务脚本 |
| `pages/home/home.vue` | H5 首页默认进入真实地点 |
| `components/tab-bar/tab-bar.vue` | H5 周边导航及选中状态 |
| `vite.config.js` | 精确开发代理与服务端安全配置 |

其余功能代码以新增服务、适配层和组件封装。原店铺接口、ShopService、数据库结构和公共请求函数沿用既有实现。

## 官方资料

[高德 POI 2.0](https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch) · [坐标转换](https://lbs.amap.com/api/javascript-api-v2/guide/transform/convertfrom) · [JS 安全配置](https://lbs.amap.com/api/javascript-api-v2/guide/abc/jscode) · [浏览器定位标准](https://www.w3.org/TR/geolocation/)
