<template>
  <view :class="['place-card', { selected }]">
    <button role="button" tabindex="0" @keydown="keyboardActivate" class="place-main" :aria-label="'在地图选中' + place.name" @click="$emit('select', place.poiId)">
      <view class="place-visual">
        <image v-if="place.photoUrl && failedPhoto !== place.photoUrl" class="place-photo" :src="place.photoUrl"
          mode="aspectFill" :lazy-load="true" :aria-label="place.name + '商家图片'" @error="failedPhoto = place.photoUrl" />
        <view v-else class="photo-placeholder"><uni-icons type="image" size="27" color="#8ba093" /><text>暂无图片</text></view>
      </view>
      <view class="place-body">
        <view class="place-heading"><text class="place-name">{{ place.name }}</text><text class="distance">{{ distance }}</text></view>
        <view class="rating-row">
          <template v-if="rating !== null"><text class="rating-star" aria-hidden="true">★</text><text class="rating-score">{{ rating }}分</text></template>
          <text v-else class="rating-missing">暂无评分</text>
        </view>
        <text class="place-type">{{ place.typeName || '分类暂无' }}</text>
        <text class="place-address">{{ place.address || '地址暂无' }}</text>
      </view>
    </button>
    <view class="place-actions">
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="action" @click="$emit('select', place.poiId)">地图定位</button>
      <button role="button" tabindex="0" @keydown="keyboardActivate" class="action" :disabled="!place.address" :aria-disabled="!place.address" @click="copyAddress">复制地址</button>
    </view>
  </view>
</template>

<script>
import { formatMeters, keyboardActivate } from '@/utils/nearby-state.js'
export default {
  props: { place: { type: Object, required: true }, selected: Boolean },
  emits: ['select'],
  data() { return { failedPhoto: '' } },
  computed: {
    distance() { return formatMeters(this.place.distanceMeters) },
    rating() {
      const value = this.place.rating
      return Number.isFinite(value) && value >= 0 && value <= 5 ? value.toFixed(1) : null
    }
  },
  methods: {
    keyboardActivate,
    copyAddress() {
      if (!this.place.address) return
      uni.setClipboardData({ data: this.place.address,
        success: () => uni.showToast({ title: '地址已复制', icon: 'none' }),
        fail: () => uni.showToast({ title: '复制失败，请手动复制地址', icon: 'none' }) })
    }
  }
}
</script>

<style scoped>
.place-card { background: #fff; border: 1px solid #e0e8e3; border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
.place-card.selected { border-color: #216b50; box-shadow: inset 3px 0 #216b50; }
.place-main { display: flex; gap: 14px; width: 100%; margin: 0; padding: 18px; text-align: left; background: transparent; border: 0; line-height: 1.5; border-radius: 0; box-sizing: border-box; }
.place-visual { flex: 0 0 96px; width: 96px; height: 104px; overflow: hidden; border-radius: 9px; background: #edf2ec; }
.place-photo { display: block; width: 100%; height: 100%; }
.photo-placeholder { display: flex; height: 100%; flex-direction: column; justify-content: center; align-items: center; gap: 6px; color: #86958a; font-size: 11px; }
.place-body { flex: 1; min-width: 0; }
.rating-row { display: flex; align-items: center; gap: 4px; margin-top: 5px; min-height: 21px; }
.rating-star, .rating-score { color: #a66b20; font-size: 14px; font-weight: 650; }
.rating-missing { color: #8a958d; font-size: 12px; }
.place-main::after, .action::after { border: 0; }
.place-main:focus-visible, .action:focus-visible { outline: 2px solid #216b50; outline-offset: -3px; }
.place-heading { display: flex; gap: 12px; align-items: flex-start; justify-content: space-between; }
.place-name { font-size: 17px; font-weight: 650; color: #203e31; overflow-wrap: anywhere; }
.distance { font-size: 13px; white-space: nowrap; color: #216b50; padding-top: 2px; }
.place-type, .place-address { display: block; font-size: 13px; color: #65776b; margin-top: 7px; overflow-wrap: anywhere; }
.place-address { color: #526158; }
.place-actions { display: flex; gap: 12px; padding: 0 18px 14px; }
.action { margin: 0; padding: 4px 12px; line-height: 1.8; font-size: 12px; color: #216b50; background: #f0f5f1; border-radius: 6px; }
.action[disabled] { color: #929c94; background: #f3f4f3; }
@media (max-width: 380px) { .place-main { padding: 14px; gap: 10px; } .place-visual { flex-basis: 76px; width: 76px; height: 92px; } .place-name { font-size: 15px; } .place-heading { gap: 6px; } .distance { font-size: 12px; } }
</style>
