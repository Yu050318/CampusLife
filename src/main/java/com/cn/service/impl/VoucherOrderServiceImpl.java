package com.cn.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cn.dto.Result;
import com.cn.entity.SeckillVoucher;
import com.cn.entity.Shop;
import com.cn.entity.Voucher;
import com.cn.entity.VoucherOrder;
import com.cn.mapper.VoucherOrderMapper;
import com.cn.service.SeckillVoucherService;
import com.cn.service.ShopService;
import com.cn.service.VoucherOrderService;
import com.cn.service.VoucherService;
import com.cn.utils.common.RedisIdWorker;
import com.cn.utils.common.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.cn.utils.cache.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 优惠券订单业务实现。
 *
 * 秒杀链路的关键点：
 * 1. Redis Lua 先原子判断库存和一人一单；
 * 2. RedisIdWorker 生成全局唯一订单号；
 * 3. Redisson 锁和数据库检查兜底防重复订单；
 * 4. 订单状态机负责支付、取消、核销、退款。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements VoucherOrderService {

    @Resource
    private SeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherService voucherService;

    @Resource
    private ShopService shopService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        // seckill.lua 放在 resources 下，脚本在 Redis 中原子执行，避免并发超卖。
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 启动秒杀订单后台消费线程，处理 Redis Stream 中的订单消息。
     */
    @PostConstruct
    private void init() {
        // 启动后台线程消费 Redis Stream 中的秒杀订单消息。
        // 当前 seckillVoucher 方法也会同步保存订单，这个线程保留为异步订单处理能力。
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // ==================== Stream 消息处理 ====================

    private class VoucherOrderHandler implements Runnable {

        /**
         * 循环读取 Redis Stream 秒杀订单消息并创建订单。
         */
        @Override
        public void run() {
            while (true) {
                try {
                    // 以消费者组方式读取 stream.orders，block 最多等待 2 秒。
                    // lastConsumed 表示读取当前消费者还没处理的新消息。
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    createVoucherOrder(voucherOrder);
                    // 业务处理成功后 ACK，否则消息会留在 pending-list，后面可以补偿处理。
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        /**
         * 补偿处理 Redis Stream 中已投递但未 ACK 的订单消息。
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // pending-list 保存“已经投递给消费者，但消费者还没 ACK”的消息。
                    // 这里从 0 开始读取，尽量把异常中断的订单补处理完。
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    createVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending订单异常", e);
                }
            }
        }
    }

    /**
     * 创建秒杀订单，使用用户维度分布式锁和数据库校验防止重复下单。
     */
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 锁粒度按用户划分：同一个用户不能并发创建多张同一优惠券订单。
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 数据库层再查一次，一人一单最终以 MySQL 中是否存在订单为准。
            long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("不允许重复下单！");
                return;
            }
            // 数据库扣库存使用 stock > 0 条件，防止数据库层出现负库存。
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足！");
                return;
            }
            // 默认状态为待支付
            voucherOrder.setStatus(1);
            voucherOrder.setShopId(resolveVoucherShopId(voucherId));
            save(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

    // ==================== 秒杀下单 ====================

    /**
     * 秒杀下单，先通过 Lua 原子校验库存和一人一单，再创建订单。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 使用 Redis 自增生成全局 ID，避免秒杀高并发都争抢数据库自增主键。
        long orderId = redisIdWorker.nextId("order");
        // Lua 返回值：0 成功；1 库存不足；2 重复下单。
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 订单由 Stream 消费者异步落库，避免高并发请求直接竞争 MySQL。
        // 用 String 返回避免 JS 长整型精度丢失。
        return Result.ok(String.valueOf(orderId));
    }

    // ==================== 普通券直接购买 ====================

    /**
     * 普通优惠券购买，校验优惠券存在和一人一券后创建待支付订单。
     */
    @Override
    public Result buyVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 检查优惠券是否存在
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 一人一券，检查是否已购买
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("您已领取过该优惠券");
        }
        // 直接创建订单
        VoucherOrder order = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setShopId(voucher.getShopId());
        order.setStatus(1); // 待支付
        save(order);
        // 用 String 返回避免 JS 长整型精度丢失
        return Result.ok(String.valueOf(orderId));
    }

    // ==================== 订单生命周期 ====================

    // 状态常量
    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_PAID = 2;
    private static final int STATUS_VERIFIED = 3;
    private static final int STATUS_CANCELED = 4;
    private static final int STATUS_REFUNDING = 5;
    private static final int STATUS_REFUNDED = 6;

    /**
     * 支付订单，校验订单归属和状态后生成核销码并标记为已支付。
     */
    @Override
    public Result payOrder(Long orderId, Integer payType) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }
        if (order.getStatus() != STATUS_UNPAID) {
            return Result.fail("订单状态不正确，无法支付");
        }
        // 模拟支付
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String verifyCode = RandomUtil.randomNumbers(6);
        order.setPayType(payType != null ? payType : 0);
        order.setStatus(STATUS_PAID);
        order.setPayTime(LocalDateTime.now());
        order.setVerifyCode(verifyCode);
        order.setUpdateTime(LocalDateTime.now());
        updateById(order);
        return Result.ok("支付成功，核销码: " + verifyCode);
    }

    /**
     * 取消待支付订单，秒杀券订单会同步恢复库存。
     */
    @Override
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }
        if (order.getStatus() != STATUS_UNPAID) {
            return Result.fail("只有待支付订单可以取消");
        }
        order.setStatus(STATUS_CANCELED);
        order.setUpdateTime(LocalDateTime.now());
        updateById(order);
        // 秒杀订单取消后恢复库存
        restoreStockIfSeckill(order.getVoucherId());
        return Result.ok();
    }

    /**
     * 核销已支付订单，校验核销码后标记订单为已使用。
     */
    @Override
    public Result useOrder(Long orderId, String verifyCode, Long shopId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != STATUS_PAID) {
            return Result.fail("订单状态不正确，无法核销");
        }
        if (order.getVerifyCode() == null || !order.getVerifyCode().equals(verifyCode)) {
            return Result.fail("核销码错误");
        }
        Long applicableShopId = resolveApplicableShopId(order);
        if (applicableShopId != null && !applicableShopId.equals(shopId)) {
            return Result.fail("\u8be5\u4f18\u60e0\u5238\u4ec5\u9650\u6307\u5b9a\u5546\u5bb6\u4f7f\u7528");
        }
        order.setStatus(STATUS_VERIFIED);
        order.setUseTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        updateById(order);
        return Result.ok();
    }

    /**
     * 退款已支付未核销订单，并在完成后恢复秒杀券库存。
     */
    @Override
    public Result refundOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }
        if (order.getStatus() != STATUS_PAID) {
            return Result.fail("只有已支付未核销的订单可以退款");
        }
        if (order.getPayType() != null && order.getPayType() == 4) {
            return Result.fail("积分兑换券暂不支持退款");
        }
        // 标记退款中
        order.setStatus(STATUS_REFUNDING);
        order.setUpdateTime(LocalDateTime.now());
        updateById(order);
        // 模拟退款处理（实际业务应接入支付渠道退款接口）
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 秒杀订单恢复库存
        restoreStockIfSeckill(order.getVoucherId());
        // 退款完成
        order.setStatus(STATUS_REFUNDED);
        order.setRefundTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        updateById(order);
        return Result.ok();
    }

    /**
     * 如果订单对应秒杀券，则同时恢复 Redis 和 MySQL 库存。
     */
    private void restoreStockIfSeckill(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher != null) {
            // 恢复 Redis 库存
            String stockKey = SECKILL_STOCK_KEY + voucherId;
            stringRedisTemplate.opsForValue().increment(stockKey);
            // 恢复数据库库存
            seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucherId)
                    .update();
        }
    }

    // ==================== 订单查询 ====================

    /**
     * 查询当前登录用户的订单列表，可按订单状态过滤。
     */
    @Override
    public Result myOrders(Integer status) {
        Long userId = UserHolder.getUser().getId();
        List<VoucherOrder> orders;
        if (status != null && status > 0) {
            orders = query().eq("user_id", userId).eq("status", status)
                    .orderByDesc("create_time").list();
        } else {
            orders = query().eq("user_id", userId)
                    .orderByDesc("create_time").list();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (VoucherOrder order : orders) {
            result.add(buildOrderResult(order));
        }
        return Result.ok(result);
    }

    /**
     * 查询当前登录用户的订单详情，并补充关联优惠券信息。
     */
    @Override
    public Result orderDetail(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权查看此订单");
        }
        // 查询关联的优惠券信息
        return Result.ok(buildOrderResult(order));
    }

    /**
     * 组装订单和优惠券展示信息，供列表页和详情页复用。
     */
    private Map<String, Object> buildOrderResult(VoucherOrder order) {
        Voucher voucher = voucherService.getById(order.getVoucherId());
        Map<String, Object> result = BeanUtil.beanToMap(order);
        result.put("voucherTitle", voucher != null ? voucher.getTitle() : "未知优惠券");
        result.put("voucherValue", voucher != null ? voucher.getActualValue() : 0);
        result.put("voucherPayValue", voucher != null ? voucher.getPayValue() : 0);
        result.put("voucherSubTitle", voucher != null ? voucher.getSubTitle() : "");
        result.put("voucherRules", voucher != null ? voucher.getRules() : "");
        boolean fromCredits = order.getPayType() != null && order.getPayType() == 4;
        Long applicableShopId = resolveApplicableShopId(order);
        result.put("shopId", applicableShopId);
        result.put("fromCredits", fromCredits);
        result.put("universal", applicableShopId == null);
        if (applicableShopId == null) {
            result.put("useScope", "\u901a\u7528\u4f18\u60e0\u5238\uff0c\u5168\u90e8\u5546\u5bb6\u53ef\u7528");
        } else {
            Shop shop = shopService.getById(applicableShopId);
            String shopName = shop != null ? shop.getName() : "\u6307\u5b9a\u5546\u5bb6";
            result.put("shopName", shopName);
            result.put("useScope", "\u4ec5\u9650" + shopName + "\u4f7f\u7528");
        }
        return result;
    }

    /**
     * Resolve the shop bound to a voucher template.
     */
    private Long resolveVoucherShopId(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        return voucher != null ? voucher.getShopId() : null;
    }

    /**
     * Preserve compatibility for records created before the scope column existed.
     */
    private Long resolveApplicableShopId(VoucherOrder order) {
        if (order.getShopId() != null) {
            return order.getShopId();
        }
        return order.getPayType() != null && order.getPayType() == 4
                ? null : resolveVoucherShopId(order.getVoucherId());
    }
}
