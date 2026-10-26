<template>
  <view class="map-shell">
    <div ref="canvas" class="map-canvas" aria-label="周边地点地图"></div>
    <view v-if="error || !ready" class="map-message" role="status">
      <text>{{ error || '正在载入地图…' }}</text>
      <button role="button" tabindex="0" @keydown="keyboardActivate" v-if="error" class="retry" @click="initialize">重试地图</button>
    </view>
    <view v-if="ready" class="map-crosshair" aria-hidden="true">＋</view>
  </view>
</template>

<script>
import { markRaw } from 'vue'
import { loadAmap, configuredCampusCenter } from '@/utils/amap-loader.js'
import { keyboardActivate } from '@/utils/nearby-state.js'
import { getBrowserPosition, convertGpsToAmap } from '@/utils/nearby-location.js'

export default {
  props: { places: { type: Array, default: () => [] }, center: Object,
    radius: { type: Number, default: 1000 }, selectedId: { type: String, default: '' } },
  emits: ['ready', 'location', 'location-error', 'locating', 'area', 'select'],
  data() { return { ready: false, error: '', map: null, amap: null, circle: null,
    markers: null, userMarker: null, generation: 0, locationVersion: 0, locating: false } },
  mounted() { this.initialize() },
  beforeUnmount() { this.dispose() },
  watch: {
    places() { this.renderPlaces() },
    center() { this.renderCircle() },
    radius() { this.renderCircle() },
    selectedId() { this.highlight() }
  },
  methods: {
    keyboardActivate,
    async initialize() {
      if (this.map || (!this.error && this.generation > 0)) return
      const generation = ++this.generation
      this.error = ''
      try {
        const amap = await loadAmap()
        if (generation !== this.generation) return
        this.amap = markRaw(amap)
        const campus = configuredCampusCenter()
        const canvas = this.$refs.canvas?.$el || this.$refs.canvas
        this.map = markRaw(new amap.Map(canvas, { viewMode: '2D', zoom: campus ? 15 : 4,
          center: campus ? [campus.longitude, campus.latitude] : [104.1, 35.9], resizeEnable: true }))
        this.markers = markRaw(new Map())
        // AMap's dragend is emitted for user dragging, not setCenter/setFitView.
        this.map.on('dragend', () => {
          const point = this.map.getCenter()
          this.$emit('area', { longitude: point.lng, latitude: point.lat })
        })
        this.ready = true
        this.renderPlaces()
        this.renderCircle()
        this.$emit('ready', campus)
        this.locate()
      } catch (error) {
        if (generation === this.generation) {
          this.error = error.message || '地图初始化失败，请重试'
          this.map?.destroy()
          this.map = null
          this.ready = false
        }
      }
    },
    async locate() {
      if (!this.map || this.locating) return
      const generation = this.generation
      const locationVersion = ++this.locationVersion
      this.locating = true
      this.$emit('locating', true)
      let point
      try {
        const gps = await getBrowserPosition(navigator.geolocation)
        if (generation !== this.generation || locationVersion !== this.locationVersion) return
        point = await convertGpsToAmap(this.amap, gps)
      } catch (error) {
        if (generation !== this.generation || locationVersion !== this.locationVersion) return
        this.locating = false
        this.$emit('locating', false)
        this.$emit('location-error', error.message)
        return
      }
      if (generation !== this.generation || locationVersion !== this.locationVersion) return
      this.locating = false
      this.$emit('locating', false)
      if (this.userMarker) this.map.remove(this.userMarker)
      const userDot = document.createElement('div')
      userDot.className = 'nearby-user-marker'
      userDot.setAttribute('aria-label', '当前位置')
      userDot.style.cssText = 'width:16px;height:16px;border:3px solid white;border-radius:50%;background:#397cc7;box-shadow:0 0 0 5px #397cc733;pointer-events:none'
      this.userMarker = markRaw(new this.amap.Marker({ position: [point.longitude, point.latitude],
        content: userDot, offset: new this.amap.Pixel(-11, -11), title: '当前位置', zIndex: 1000 }))
      this.map.add(this.userMarker)
      this.map.setZoomAndCenter(15, [point.longitude, point.latitude])
      this.$emit('location', point)
    },
    cancelLocation() {
      // Native getCurrentPosition has no cancellation handle; invalidate its result instead.
      this.locationVersion++
      this.locating = false
      this.$emit('locating', false)
    },
    renderPlaces() {
      if (!this.map) return
      for (const marker of this.markers.values()) { marker.off('click'); this.map.remove(marker) }
      this.markers.clear()
      for (const [index, place] of this.places.entries()) {
        const content = document.createElement('div')
        content.className = 'nearby-poi-marker'
        content.textContent = String(index + 1)
        content.style.cssText = 'border:2px solid #216b50;border-radius:50%;width:26px;height:26px;text-align:center;line-height:26px;font-size:12px;font-weight:700;box-shadow:0 2px 6px #0002'
        const marker = markRaw(new this.amap.Marker({ position: [place.longitude, place.latitude], title: place.name,
          content, offset: new this.amap.Pixel(-15, -15) }))
        marker.on('click', () => this.$emit('select', place.poiId))
        this.markers.set(place.poiId, marker)
        this.map.add(marker)
      }
      this.highlight()
    },
    renderCircle() {
      if (!this.map) return
      if (this.circle) this.map.remove(this.circle)
      this.circle = null
      if (this.center) {
        this.circle = markRaw(new this.amap.Circle({ center: [this.center.longitude, this.center.latitude],
          radius: this.radius, strokeColor: '#216b50', strokeWeight: 1, fillColor: '#216b50', fillOpacity: 0.06 }))
        this.map.add(this.circle)
      }
    },
    highlight() {
      if (!this.markers) return
      for (const [id, marker] of this.markers) {
        marker.setZIndex(id === this.selectedId ? 200 : 100)
        const element = marker.getContent()
        element.style.background = id === this.selectedId ? '#216b50' : '#fff'
        element.style.color = id === this.selectedId ? '#fff' : '#216b50'
      }
    },
    focusPlace(id) {
      const marker = this.markers?.get(id)
      if (marker) this.map.setCenter(marker.getPosition())
    },
    fitResults() {
      if (!this.map) return
      const overlays = [...this.markers.values()]
      if (this.circle) overlays.push(this.circle)
      if (overlays.length) this.map.setFitView(overlays, true, [24, 24, 24, 24], 16)
    },
    dispose() {
      this.generation++
      this.map?.destroy()
      this.map = null
      this.ready = false
      this.locating = false
    }
  }
}
</script>

<style scoped>
.map-shell { position: relative; height: clamp(240px, 35vh, 360px); overflow: hidden; border-radius: 14px; background: #e8eee8; border: 1px solid #d6e1d9; }
.map-canvas { width: 100%; height: 100%; }
.map-message { position: absolute; inset: 0; display: flex; flex-direction: column; gap: 14px; align-items: center; justify-content: center; text-align: center; padding: 24px; background: #edf2eb; color: #52685a; font-size: 14px; }
.retry { margin: 0; padding: 5px 18px; font-size: 13px; background: #216b50; color: #fff; line-height: 2; }
.map-crosshair { pointer-events: none; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-size: 26px; line-height: 1; color: #163c2a; text-shadow: 0 0 3px white; }
</style>
