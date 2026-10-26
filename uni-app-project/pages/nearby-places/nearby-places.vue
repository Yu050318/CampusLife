<template>
  <view class="nearby-places">
    <view class="page-heading">
      <text class="eyebrow">CAMPUSLIFE · EXPLORE</text>
      <text class="title">走出校园，发现周边</text>
      <text class="intro">寻找一顿好饭、一间小店，或日常所需。</text>
      <navigator class="internal-shops" open-type="switchTab" url="/pages/nearby/nearby">站内店铺 ↗</navigator>
    </view>

    <view class="search-row">
      <input v-model="keywordInput" class="keyword" placeholder="在所选区域搜索地点" maxlength="80"
        confirm-type="search" aria-label="地点关键词" @confirm="submitKeyword" />
      <button role="button" tabindex="0" @keydown="keyboardActivate" v-if="keywordInput || keyword" class="clear-button" aria-label="清空关键词" @click="clearKeyword">清空</button>
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="primary search-button" @click="submitKeyword">搜索</button>
    </view>
    <view class="filters">
      <view class="categories" role="group" aria-label="地点分类">
        <button role="button" tabindex="0" @keydown="keyboardActivate" v-for="item in categories" :key="item.id" :class="['chip', { active: category === item.id }]"
          :aria-pressed="category === item.id" @click="changeCategory(item.id)">{{ item.name }}</button>
      </view>
      <view class="radii" role="group" aria-label="搜索半径">
        <button role="button" tabindex="0" @keydown="keyboardActivate" v-for="value in [1000, 3000, 5000]" :key="value" :class="['radius-button', { active: radius === value }]"
          :aria-pressed="radius === value" @click="changeRadius(value)">{{ value / 1000 }}公里</button>
      </view>
    </view>

    <view class="center-row">
      <text>{{ searchCenter ? (centerSource === 'location' ? '以当前位置搜索' : '以地图选点搜索') : '请定位，或拖动地图选择搜索区域' }}</text>
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="text-button" :disabled="!mapReady || locating" :aria-disabled="!mapReady || locating" @click="$refs.map.locate()">{{ locating ? '定位中…' : '重新定位' }}</button>
    </view>
    <nearby-map ref="map" :places="query.items" :center="resultCenter" :radius="resultRadius" :selected-id="selectedPoiId"
      @ready="mapInitialized" @location="located" @location-error="locationFailed" @locating="locating = $event"
      @area="areaChanged" @select="selectPlace" />
    <view class="area-actions" v-if="areaCenter">
      <text>地图已选点，点击后更新查询</text>
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="primary" @click="searchArea">搜索此区域</button>
    </view>
    <text v-if="locationMessage" class="location-message" role="status">{{ locationMessage }}</text>

    <view class="results-header">
      <text class="section-title">周边地点 <text class="count">{{ query.items.length }}</text></text>
      <text class="result-caption">{{ resultSummary }}</text>
    </view>
    <view v-if="query.loading" class="notice" role="status">{{ query.page && !refreshing ? '正在加载更多…' : '正在更新地点…' }}</view>
    <view v-if="query.error" class="notice error" role="alert">
      <text>{{ query.error }}</text>
      <text v-if="query.items.length && query.refreshFailed">更新失败，当前仍为上次结果：{{ resultSummary }}</text>
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="text-button" :disabled="query.loading" :aria-disabled="query.loading" @click="retry">重试查询</button>
    </view>
    <view v-if="!query.loading && !query.error && !query.items.length" class="empty">
      <uni-icons type="location" size="38" color="#6d8a75" />
      <text>{{ query.conditions ? '这个范围暂未找到相关地点' : '从你所在的地方开始探索' }}</text>
      <text class="empty-help">{{ query.conditions ? '试试其他分类、关键词或更大的搜索范围' : '允许定位，或在地图上选点后搜索' }}</text>
    </view>
    <view v-for="(place, index) in query.items" :key="place.poiId" :id="'nearby-place-' + index" class="place-anchor">
      <nearby-place-card :place="place" :selected="selectedPoiId === place.poiId" @select="selectPlace" />
    </view>
    <button role="button" tabindex="0" @keydown="keyboardActivate" v-if="query.hasMore" class="more-button" :disabled="query.loading || query.refreshFailed" :aria-disabled="query.loading || query.refreshFailed" @click="fetchPlaces(false)">加载更多</button>
    <text v-else-if="query.items.length" class="end-caption">{{ query.page >= 5 ? '已展示本次搜索结果，请缩小范围或调整分类' : '本次搜索结果已加载完毕' }}</text>
    <text class="attribution">地点、图片及评分来自高德地图 · 距离以搜索中心为准</text>
    <tab-bar current="/pages/nearby-places/nearby-places" />
  </view>
</template>

<script>
import NearbyMap from '@/components/nearby-map/nearby-map.vue'
import NearbyPlaceCard from '@/components/nearby-place-card/nearby-place-card.vue'
import { getNearbyPlaces } from '@/api/nearby.js'
import { createNearbyState, loadNearby, keyboardActivate } from '@/utils/nearby-state.js'

const categories = [{ id: 'food', name: '餐饮' }, { id: 'shopping', name: '购物' }, { id: 'life', name: '生活服务' }]

export default {
  components: { NearbyMap, NearbyPlaceCard },
  data() {
    return { categories, category: 'food', radius: 1000, keywordInput: '', keyword: '',
      searchCenter: null, centerSource: '', userLocation: null, areaCenter: null,
      selectedPoiId: '', locationMessage: '', mapReady: false, locating: false,
      query: createNearbyState(), refreshing: false, visible: true }
  },
  computed: {
    resultCenter() { return this.query.conditions },
    resultRadius() { return this.query.conditions?.radius || this.radius },
    resultSummary() {
      const conditions = this.query.conditions
      if (!conditions) return '按距搜索中心的距离排序'
      const category = categories.find(item => item.id === conditions.category)?.name || ''
      return `${category} · ${conditions.radius / 1000}公里${conditions.keyword ? ' · ' + conditions.keyword : ''}`
    }
  },
  onShow() { this.visible = true },
  onHide() { this.invalidate() },
  onUnload() { this.invalidate() },
  methods: {
    keyboardActivate,
    invalidate() {
      this.visible = false
      this.$refs.map?.cancelLocation()
      this.query.version++
      if (this.query.loading && this.refreshing) {
        this.query.refreshFailed = true
        this.query.error = '上次更新已中断，请重试查询'
      }
      this.query.loading = false
    },
    mapInitialized(campus) {
      this.mapReady = true
      if (campus && !this.searchCenter) this.areaCenter = campus
    },
    located(point) {
      if (!this.visible) return
      this.userLocation = point
      this.searchCenter = point
      this.centerSource = 'location'
      this.areaCenter = null
      this.locationMessage = ''
      this.fetchPlaces(true)
    },
    locationFailed(message) { if (this.visible) this.locationMessage = message },
    areaChanged(point) { this.areaCenter = point },
    searchArea() {
      this.$refs.map?.cancelLocation()
      this.searchCenter = { ...this.areaCenter }
      this.centerSource = 'map'
      this.areaCenter = null
      this.fetchPlaces(true)
    },
    changeCategory(category) {
      if (this.category === category) return
      this.category = category
      this.fetchPlaces(true)
    },
    changeRadius(radius) {
      if (this.radius === radius) return
      this.radius = radius
      this.fetchPlaces(true)
    },
    submitKeyword() { this.keyword = this.keywordInput.trim(); this.fetchPlaces(true) },
    clearKeyword() {
      this.keywordInput = ''
      if (!this.keyword) return
      this.keyword = ''
      this.fetchPlaces(true)
    },
    async fetchPlaces(refresh = true) {
      if (!this.visible || !this.searchCenter) return
      if (!refresh && (this.query.loading || this.query.refreshFailed || !this.query.hasMore)) return
      this.refreshing = refresh
      if (refresh) this.selectedPoiId = ''
      const conditions = { ...this.searchCenter, category: this.category, radius: this.radius, keyword: this.keyword }
      const applied = await loadNearby(this.query, getNearbyPlaces, conditions, refresh)
      if (applied && refresh && this.visible) {
        await this.$nextTick()
        this.$refs.map?.fitResults()
      }
    },
    retry() { if (!this.query.loading) this.fetchPlaces(this.query.refreshFailed || this.query.page === 0) },
    selectPlace(id) {
      this.selectedPoiId = id
      this.$refs.map?.focusPlace(id)
      const index = this.query.items.findIndex(item => item.poiId === id)
      if (index >= 0) uni.pageScrollTo({ selector: '#nearby-place-' + index, duration: 250 })
    }
  }
}
</script>

<style scoped>
.nearby-places { max-width: 780px; margin: 0 auto; padding: 24px 20px calc(90px + env(safe-area-inset-bottom)); color: #243d2d; background: #f6f8f3; min-height: 100vh; box-sizing: border-box; }
.internal-shops { display: inline-block; margin-top: 12px; color: #216b50; font-size: 13px; text-decoration: underline; }
.page-heading { margin: 4px 0 24px; }
.eyebrow { display: block; color: #6c806d; font-size: 10px; letter-spacing: 2px; margin-bottom: 10px; }
.title { display: block; font-size: 27px; font-weight: 700; letter-spacing: -.6px; line-height: 1.35; }
.intro { display: block; margin-top: 9px; font-size: 13px; color: #728071; }
.search-row { display: flex; align-items: center; padding: 5px; background: #fff; border: 1px solid #dce4d8; border-radius: 10px; gap: 4px; }
.keyword { flex: 1; min-width: 0; padding: 8px; font-size: 14px; height: 28px; }
button { font-size: 13px; border-radius: 7px; margin: 0; }
button::after { border: 0; }
button:focus-visible { outline: 2px solid #216b50; outline-offset: 2px; }
.primary { background: #216b50; color: #fff; padding: 5px 16px; line-height: 2; }
.clear-button { background: transparent; padding: 6px; font-size: 12px; color: #74816f; line-height: 2; }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; margin: 16px 0 8px; }
.categories, .radii { display: flex; gap: 6px; }
.chip { padding: 7px 13px; line-height: 1.7; background: #eaf0e5; color: #596f58; }
.chip.active { background: #216b50; color: #fff; }
.radius-button { padding: 5px 7px; line-height: 1.7; background: transparent; color: #74816f; font-size: 12px; }
.radius-button.active { color: #216b50; background: #e5eddf; font-weight: 700; }
.center-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; font-size: 12px; color: #657761; padding: 7px 0; }
.text-button { color: #216b50; background: transparent; padding: 5px 0; line-height: 1.8; white-space: nowrap; }
.text-button[disabled] { color: #869282; }
.area-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; font-size: 12px; color: #62715f; }
.location-message { display: block; color: #886b37; font-size: 12px; line-height: 1.6; margin-top: 10px; }
.results-header { margin: 26px 0 14px; display: flex; gap: 10px; flex-wrap: wrap; justify-content: space-between; align-items: center; }
.section-title { font-size: 18px; font-weight: 650; }
.count { font-size: 12px; color: #6b806b; font-weight: 400; margin-left: 6px; }
.result-caption { color: #758471; font-size: 11px; overflow-wrap: anywhere; }
.notice { background: #eaf0e5; color: #52694c; border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; font-size: 13px; line-height: 1.7; }
.notice.error { background: #fbf0de; color: #805e32; }
.notice text { display: block; }
.empty { display: flex; align-items: center; flex-direction: column; gap: 12px; padding: 38px 16px; font-size: 15px; color: #587052; text-align: center; }
.empty-help { font-size: 12px; color: #87917e; }
.place-anchor { scroll-margin-top: 18px; }
.more-button { background: #e5eddf; color: #216b50; padding: 7px; line-height: 2; }
.end-caption, .attribution { display: block; text-align: center; font-size: 11px; color: #84907f; margin-top: 22px; line-height: 1.7; }
@media (max-width: 380px) { .nearby-places { padding: 18px 14px 30px; } .title { font-size: 24px; } }
@media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto; } }
</style>
