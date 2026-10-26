<template>
	<view class="container">
		<view class="header-fixed">
			<view class="status-bar"></view>
			<view class="nav-row">
				<view class="brand"><text class="brand-mark">邻</text><text class="nav-title">校邻</text><text class="brand-en">CAMPUSLIFE</text></view>
				<text class="campus-label">华农 · 五山</text>
			</view>
		</view>

		<view class="campus-hero">
			<text class="hero-eyebrow">HELLO, CAMPUS</text>
			<view class="hero-title"><text>课外的精彩，</text><text>就在你身边。</text></view>
			<text class="hero-copy">记录日常小确幸，发现校园新去处。</text>
			<view class="hero-search" @click="goSearch"><uni-icons type="search" size="20" color="#216b50" /><text>搜一搜，发现身边好店</text><uni-icons type="right" size="16" color="#216b50" /></view>
			<text class="hero-decoration">邻</text>
		</view>
		<view class="quick-links">
			<view class="quick-link" @click="goNearby"><view class="quick-icon"><uni-icons type="location" size="25" color="#216b50" /></view><text class="quick-title">周边好店</text><text class="quick-copy">下课去哪儿</text></view>
			<view class="quick-link" @click="goCredits"><view class="quick-icon"><uni-icons type="gift" size="25" color="#216b50" /></view><text class="quick-title">积分小铺</text><text class="quick-copy">攒点小惊喜</text></view>
			<view class="quick-link" @click="goPublish"><view class="quick-icon"><uni-icons type="compose" size="25" color="#216b50" /></view><text class="quick-title">分享日常</text><text class="quick-copy">留住好时光</text></view>
		</view>
		<view class="section-heading"><text class="section-title">校园生活手记</text><text class="section-caption">每一种日常，都值得分享</text></view>
		<view class="tabs-fixed">
			<view class="tabs-row">
				<view :class="['tabs-item', currentTab === 'hot' ? 'active' : '']" @click="switchTab('hot')"><text>发现精彩</text></view>
				<view :class="['tabs-item', currentTab === 'follow' ? 'active' : '']" @click="switchTab('follow')"><text>关注</text></view>
			</view>
		</view>

		<!-- 热门 -->
		<block v-if="currentTab === 'hot'">
			<view class="feed-list">
				<blog-card v-for="blog in hotBlogs" :key="blog.id" :blog="blog" />
				<view v-if="hotLoading && hotBlogs.length === 0" class="load-tip"><text>加载中...</text></view>
				<view v-if="!hotLoading && hotBlogs.length === 0" class="empty-wrap"><uni-icons class="empty-icon" type="compose" size="52" color="#9cb7a8" /><text class="empty-text">这里，等你写下第一篇生活手记</text><view class="login-btn" @click="goPublish">分享我的日常</view></view>
				<view v-if="!hotLoading && hotNoMore && hotBlogs.length > 0" class="load-tip no-more"><text>— 已经到底了 —</text></view>
			</view>
		</block>

		<!-- 关注 -->
		<block v-if="currentTab === 'follow'">
			<view v-if="!isLogin" class="empty-wrap" style="padding-top:200rpx">
				<uni-icons class="empty-icon" type="person" size="64" color="#ddd" /><text class="empty-text">登录后查看关注动态</text>
				<view class="login-btn" @click="goLogin">去登录</view>
			</view>
			<view v-else class="feed-list">
				<blog-card v-for="blog in followBlogs" :key="blog.id" :blog="blog" />
				<view v-if="followLoading && followBlogs.length === 0" class="load-tip"><text>加载中...</text></view>
				<view v-if="!followLoading && followBlogs.length === 0" class="empty-wrap"><uni-icons class="empty-icon" type="notification" size="64" color="#ddd" /><text class="empty-text">关注一些用户，发现更多精彩</text></view>
				<view v-if="!followLoading && followNoMore && followBlogs.length > 0" class="load-tip no-more"><text>— 已经到底了 —</text></view>
			</view>
		</block>

		<view class="project-note">校邻 CampusLife · 个人学习项目，非华南农业大学官方平台</view>
		<tab-bar current="/pages/home/home" />
	</view>
</template>

<script>
import { getHotBlogs, getFollowBlogs } from '@/api/blog.js'
import { isLogin } from '@/utils/auth.js'

// 页面级缓存，tab 切换不丢失
const cache = { hotBlogs: [], followBlogs: [], followLastId: 0, followOffset: 0 }

export default {
	data() {
		return {
			currentTab: 'hot',
			isLogin: false,
			hotBlogs: cache.hotBlogs,
			hotCurrent: 1,
			hotLoading: false,
			hotNoMore: false,
			followBlogs: cache.followBlogs,
			followLastId: cache.followLastId || Date.now(),
			followOffset: cache.followOffset || 0,
			followLoading: false,
			followNoMore: false
		}
	},
	onLoad() {
		// 详情页/用户主页关注状态变化后会触发该事件，首页收到后刷新关注流。
		uni.$on('followChanged', this.handleFollowChanged)
	},
	onUnload() {
		// 页面销毁时解绑，避免重复监听导致一次关注触发多次刷新。
		uni.$off('followChanged', this.handleFollowChanged)
	},
	onShow() {
		this.isLogin = isLogin()
		// 有缓存直接展示，后台静默刷新
		if (this.hotBlogs.length === 0) this.loadHotBlogs(true)
		if (this.isLogin) this.loadFollowBlogs(true)
	},
	mounted() {
		// 首次进入预加载两个 tab 的数据
		this.loadHotBlogs(true)
		if (this.isLogin) this.loadFollowBlogs(true)
	},
	methods: {
		goSearch() { uni.navigateTo({ url: '/pages/search/search' }) },
		goNearby() {
			// #ifdef H5
			uni.navigateTo({ url: '/pages/nearby-places/nearby-places' })
			// #endif
			// #ifndef H5
			uni.switchTab({ url: '/pages/nearby/nearby' })
			// #endif
		},
		goCredits() { uni.navigateTo({ url: '/pages/credits-shop/credits-shop' }) },
		goPublish() { uni.navigateTo({ url: this.isLogin ? '/pages/publish-blog/publish-blog' : '/pages/login/login' }) },
		handleFollowChanged() {
			if (!this.isLogin) return
			this.loadFollowBlogs(true)
		},
		switchTab(tab) {
			this.currentTab = tab
			if (tab === 'follow' && this.isLogin && this.followBlogs.length === 0) this.loadFollowBlogs(true)
		},
		async loadHotBlogs(refresh) {
			if (this.hotLoading) return
			if (refresh) { this.hotCurrent = 1; this.hotNoMore = false }
			if (this.hotNoMore && !refresh) return
			this.hotLoading = true
			try {
				const res = await getHotBlogs(this.hotCurrent)
				if (res.success) {
					const list = res.data || []
					this.hotBlogs = refresh ? list : [...this.hotBlogs, ...list]
					cache.hotBlogs = this.hotBlogs
					if (list.length < 10) this.hotNoMore = true
				}
			} catch (e) {} finally { this.hotLoading = false }
		},
		async loadFollowBlogs(refresh) {
			if (!this.isLogin || this.followLoading) return
			// refresh 表示重新拉第一页；非 refresh 表示继续用 lastId/offset 加载下一页。
			if (refresh) { this.followLastId = Date.now(); this.followOffset = 0; this.followNoMore = false }
			if (this.followNoMore && !refresh) return
			this.followLoading = true
			try {
				const res = await getFollowBlogs(this.followLastId, this.followOffset)
				if (res.success && res.data && res.data.list && res.data.list.length > 0) {
					this.followBlogs = refresh ? res.data.list : [...this.followBlogs, ...res.data.list]
					cache.followBlogs = this.followBlogs
					this.followLastId = res.data.minTime || 0
					this.followOffset = res.data.offset || 0
					cache.followLastId = this.followLastId
					cache.followOffset = this.followOffset
				} else if (refresh) {
					// 刷新结果为空时也要同步清空页面缓存，否则旧关注流会残留。
					this.followBlogs = []
					cache.followBlogs = []
					cache.followLastId = this.followLastId
					cache.followOffset = this.followOffset
				}
				if (!res.data || !res.data.list || res.data.list.length < 5) this.followNoMore = true
			} catch (e) {} finally { this.followLoading = false }
		},
		goLogin() { uni.navigateTo({ url: '/pages/login/login' }) }
	},
	onReachBottom() {
		if (this.currentTab === 'hot') { this.hotCurrent++; this.loadHotBlogs(false) }
		else { this.loadFollowBlogs(false) }
	},
	onPullDownRefresh() {
		if (this.currentTab === 'hot') this.loadHotBlogs(true)
		else this.loadFollowBlogs(true)
		setTimeout(() => uni.stopPullDownRefresh(), 500)
	}
}
</script>

<style scoped>
.container { background: #f6f7f2; min-height: 100vh; padding-bottom: calc(120rpx + env(safe-area-inset-bottom)); }
.header-fixed { position: sticky; top: 0; z-index: 200; background: #fff; }
.status-bar { height: var(--status-bar-height); }
.nav-row { height: 104rpx; padding: 0 32rpx; display: flex; align-items: center; justify-content: space-between; }
.brand { display: flex; align-items: center; gap: 12rpx; }
.brand-mark { width: 58rpx; height: 58rpx; line-height: 58rpx; text-align: center; background: #216b50; color: #fff; border-radius: 18rpx 18rpx 6rpx 18rpx; font-size: 34rpx; font-weight: 700; }
.brand-en { font-size: 16rpx; letter-spacing: 2rpx; color: #718478; }
.campus-label { font-size: 22rpx; color: #216b50; background: #edf5ef; padding: 10rpx 16rpx; border-radius: 24rpx; }
.campus-hero { margin: 24rpx 24rpx 0; padding: 40rpx 32rpx 28rpx; background: #e5eee2; border-radius: 28rpx; overflow: hidden; position: relative; }
.hero-eyebrow { display: block; font-size: 20rpx; letter-spacing: 4rpx; color: #52745a; }
.hero-title { display: block; white-space: pre-line; font-size: 54rpx; line-height: 1.35; font-weight: 800; color: #214c38; margin: 20rpx 0; position: relative; z-index: 1; }
.hero-title text { display: block; }
.hero-copy { display: block; font-size: 25rpx; color: #58735f; position: relative; z-index: 1; }
.hero-decoration { position: absolute; right: -14rpx; top: 12rpx; font-size: 260rpx; font-weight: 900; color: #d0dfc9; transform: rotate(-12deg); pointer-events: none; }
.hero-search { display: flex; align-items: center; gap: 16rpx; padding: 24rpx; margin-top: 36rpx; background: #fff; border-radius: 16rpx; position: relative; z-index: 1; }
.hero-search text { flex: 1; font-size: 24rpx; color: #718478; }
.quick-links { display: flex; gap: 16rpx; margin: 22rpx 24rpx 36rpx; }
.quick-link { flex: 1; background: #fff; border: 1rpx solid #e6ebe3; border-radius: 22rpx; padding: 24rpx 12rpx; display: flex; flex-direction: column; align-items: center; }
.quick-icon { margin-bottom: 12rpx; }
.quick-title { font-size: 27rpx; font-weight: 600; color: #274634; }
.quick-copy { font-size: 20rpx; color: #899286; margin-top: 8rpx; }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; padding: 0 28rpx 12rpx; gap: 12rpx; }
.section-title { font-size: 34rpx; font-weight: 700; color: #274634; }
.section-caption { font-size: 20rpx; color: #899286; }
.project-note { padding: 24rpx 32rpx; text-align: center; font-size: 19rpx; line-height: 1.8; color: #899286; }
.nav-title { font-size: 34rpx; font-weight: 700; color: #333; }
.tabs-fixed { position: sticky; top: calc(var(--status-bar-height) + 104rpx); z-index: 199; background: #fff; border-bottom: 1rpx solid #f0f0f0; }
.tabs-row { display: flex; justify-content: center; }
.tabs-item { padding: 20rpx 48rpx; position: relative; }
.tabs-item text { font-size: 30rpx; color: #999; font-weight: 500; }
.tabs-item.active text { color: #333; font-weight: 700; }
.tabs-item.active::after { content: ''; position: absolute; bottom: 10rpx; left: 50%; transform: translateX(-50%); width: 40rpx; height: 6rpx; background: #216b50; border-radius: 3rpx; }
.feed-list { padding: 20rpx 20rpx 0; }
.load-tip { display: flex; justify-content: center; padding: 30rpx; font-size: 24rpx; color: #999; }
.load-tip.no-more { color: #ccc; }
.empty-wrap { display: flex; flex-direction: column; align-items: center; padding: 120rpx 0; }
.empty-icon { display: block; margin-bottom: 20rpx; }
.empty-text { font-size: 28rpx; color: #999; }
.login-btn { margin-top: 32rpx; padding: 16rpx 60rpx; background: linear-gradient(135deg, #216b50, #398361); color: #fff; border-radius: 40rpx; font-size: 28rpx; }
</style>
