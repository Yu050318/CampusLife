package com.cn.nearby;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt in only against the isolated test Redis; no MySQL or paid upstream calls. */
@EnabledIfEnvironmentVariable(named = "NEARBY_REDIS_TEST", matches = "true")
class NearbyRedisIntegrationTest {
    @Test
    @Timeout(90)
    void actualLuaRejectsThirtyFirstRequestAndRecoversAfterRealWindowExpiry() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("NEARBY_TEST_REDIS_PORT", "6379"));
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        String ip = "2001:db8:" + UUID.randomUUID().toString().substring(0, 4) + "::1";
        String key = "nearby:rate:" + DigestUtils.md5DigestAsHex(ip.getBytes(StandardCharsets.UTF_8));
        try {
            var properties = mock(NearbyProperties.class);
            when(properties.getTrustedProxies()).thenReturn("");
            when(properties.getRateLimit()).thenReturn(30);
            var limiter = new NearbyRateLimiter(redis, properties);
            var request = new MockHttpServletRequest();
            request.setRemoteAddr(ip);
            for (int i = 0; i < 30; i++) limiter.check(request);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                    assertThrows(ResponseStatusException.class, () -> limiter.check(request)).getStatusCode());
            assertEquals("31", redis.opsForValue().get(key));
            Long ttl = redis.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl > 50 && ttl <= 60, "Lua must atomically create the 60-second TTL");
            // Observe Redis expiry rather than editing TTL or clearing the counter to fake recovery.
            long deadline = System.nanoTime() + Duration.ofSeconds(65).toNanos();
            while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(1000);
            assertFalse(redis.hasKey(key));
            limiter.check(request);
            assertEquals("1", redis.opsForValue().get(key));
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }
}
