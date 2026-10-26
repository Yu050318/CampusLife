<template>
	<view class="container">
		<view class="header" :style="{ paddingTop: statusBarH + 'px' }">
			<view class="nav-bar">
				<view class="nav-back" @click="goBack">
					<view class="back-circle"><uni-icons type="left" size="18" color="#333"></uni-icons></view>
				</view>
				<text class="nav-title">确认支付</text>
			</view>
		</view>

		<view class="content">
			<!-- 订单信息 -->
			<view class="order-card">
				<view class="order-header">
					<uni-icons type="checkbox-filled" size="48" color="#216b50"></uni-icons>
					<text class="order-title">订单已生成</text>
				</view>
				<view class="order-row">
					<text class="label">订单编号</text>
					<text class="value">{{ orderId }}</text>
				</view>
				<view class="order-row">
					<text class="label">商品名称</text>
					<text class="value">城市点评优惠券</text>
				</view>
				<view class="order-row">
					<text class="label">支付金额</text>
					<text class="value amount">¥{{ amount }}</text>
				</view>
			</view>

			<!-- 支付按钮 -->
			<view class="pay-actions">
				<button class="btn-pay" :disabled="paying" @click="confirmPay">
					{{ paying ? '处理中...' : '确认支付 ¥' + amount }}
				</button>
				<text class="cancel-text" @click="goBack">取消支付</text>
			</view>

			<view class="mock-tip">
				<uni-icons type="info-filled" size="14" color="#999"></uni-icons>
				<text>模拟支付，点击即支付成功</text>
			</view>
		</view>
	</view>
</template>

<script>
import { mockPayApi } from '@/api/voucher.js'

export default {
	data() {
		return {
			orderId: '',
			amount: '0.01',
			statusBarH: 0,
			paying: false
		}
	},
	onLoad(options) {
		this.statusBarH = uni.getSystemInfoSync().statusBarHeight || 0
		if (options.orderId) {
			this.orderId = options.orderId
		}
		if (options.amount) {
			this.amount = options.amount
		}
	},
	methods: {
		async confirmPay() {
			if (this.paying) return
			this.paying = true
			uni.showLoading({ title: '支付中...' })
			try {
				const res = await mockPayApi(this.orderId)
				uni.hideLoading()
				if (res.success) {
					uni.showToast({ title: '支付成功！', icon: 'success', duration: 1500 })
					setTimeout(() => {
						uni.navigateBack()
					}, 1500)
				} else {
					uni.showToast({ title: res.errorMsg || '支付失败', icon: 'none' })
					this.paying = false
				}
			} catch (e) {
				uni.hideLoading()
				uni.showToast({ title: '支付失败，请重试', icon: 'none' })
				this.paying = false
			}
		},
		goBack() {
			uni.navigateBack()
		}
	}
}
</script>

<style scoped>
.container {
	background-color: #f5f5f5;
	min-height: 100vh;
}

.header {
	background-color: #ffffff;
	position: sticky;
	top: 0;
	z-index: 100;
}

.nav-bar {
	height: 88rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	position: relative;
}

.nav-back {
	position: absolute;
	left: 20rpx;
	top: 50%;
	transform: translateY(-50%);
	padding: 10rpx;
}

.back-circle {
	width: 60rpx;
	height: 60rpx;
	border-radius: 50%;
	background-color: rgba(0, 0, 0, 0.05);
	display: flex;
	align-items: center;
	justify-content: center;
}

.nav-title {
	font-size: 32rpx;
	font-weight: 600;
}

.content {
	padding: 20rpx;
	padding-top: 60rpx;
}

.order-card {
	background-color: #ffffff;
	border-radius: 16rpx;
	padding: 32rpx 24rpx;
	margin-bottom: 60rpx;
}

.order-header {
	display: flex;
	flex-direction: column;
	align-items: center;
	margin-bottom: 32rpx;
}

.order-title {
	font-size: 30rpx;
	font-weight: 600;
	color: #333;
	margin-top: 12rpx;
}

.order-row {
	display: flex;
	justify-content: space-between;
	padding: 16rpx 0;
	border-bottom: 1rpx solid #f5f5f5;
}

.order-row:last-child {
	border-bottom: none;
}

.label {
	font-size: 26rpx;
	color: #999;
}

.value {
	font-size: 26rpx;
	color: #333;
}

.amount {
	font-size: 32rpx;
	font-weight: 700;
	color: #216b50;
}

.pay-actions {
	padding: 0 20rpx;
	display: flex;
	flex-direction: column;
	align-items: center;
}

.btn-pay {
	width: 100%;
	height: 88rpx;
	line-height: 88rpx;
	background: linear-gradient(135deg, #216b50, #398361);
	color: #ffffff;
	border-radius: 44rpx;
	font-size: 32rpx;
	font-weight: 600;
	border: none;
	padding: 0;
	margin-bottom: 24rpx;
}

.btn-pay::after {
	border: none;
}

.btn-pay[disabled] {
	background: #ccc;
}

.cancel-text {
	font-size: 26rpx;
	color: #999;
}

.mock-tip {
	margin-top: 40rpx;
	padding: 16rpx 24rpx;
	background-color: #f5f5f5;
	border-radius: 12rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 8rpx;
	font-size: 22rpx;
	color: #999;
}
</style>
