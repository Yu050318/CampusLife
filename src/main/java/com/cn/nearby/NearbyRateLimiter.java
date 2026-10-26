package com.cn.nearby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class NearbyRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final NearbyProperties properties;

    public NearbyRateLimiter(StringRedisTemplate redis, NearbyProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        boolean trusted = Arrays.stream(properties.getTrustedProxies().split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).anyMatch(ip::equals);
        if (trusted) {
            // Proxies must overwrite this header; do not trust an incoming header chain.
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && forwarded.matches("[0-9a-fA-F:.]{3,45}")) ip = forwarded;
        }
        String key = "nearby:rate:" + DigestUtils.md5DigestAsHex(ip.getBytes(StandardCharsets.UTF_8));
        Long count;
        try {
            count = redis.execute(INCREMENT, List.of(key));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "周边地点服务繁忙，请稍后重试");
        }
        if (count == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "周边地点服务暂不可用");
        if (count > Math.max(1, properties.getRateLimit())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "查询过于频繁，请稍后重试");
        }
    }
}
