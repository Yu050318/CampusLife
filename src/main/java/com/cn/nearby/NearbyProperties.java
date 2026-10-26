package com.cn.nearby;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class NearbyProperties {
    @Value("${AMAP_WEB_KEY:}")
    private String webKey;

    @Value("${NEARBY_RATE_LIMIT:30}")
    private int rateLimit;

    // Only exact addresses of deployment proxies are trusted, never arbitrary X-Forwarded-For.
    @Value("${NEARBY_TRUSTED_PROXIES:}")
    private String trustedProxies;
}
