package com.cn.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Service
public class AmapNearbyService {
    static final int PAGE_SIZE = 20;
    private static final Map<String, String> TYPES = Map.of(
            "food", "050000", "shopping", "060000", "life", "070000");
    private final NearbyProperties properties;
    private final RestClient client;

    public AmapNearbyService(NearbyProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.client = builder.clone().baseUrl("https://restapi.amap.com")
                .requestFactory(factory).build();
    }

    public static String validate(Double longitude, Double latitude, String category,
                                  int radius, String keyword, int page) {
        if (longitude == null || latitude == null || !Double.isFinite(longitude)
                || !Double.isFinite(latitude) || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效经纬度");
        }
        if (!TYPES.containsKey(category) || (radius != 1000 && radius != 3000 && radius != 5000)
                || page < 1 || page > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类、半径或页码无效");
        }
        String trimmed = keyword == null ? "" : keyword.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键词最多80个字符");
        }
        return trimmed;
    }

    public NearbyPage search(double longitude, double latitude, String category,
                             int radius, String keyword, int page) {
        if (properties.getWebKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "周边地点服务尚未配置");
        }
        long started = System.nanoTime();
        JsonNode body;
        try {
            body = client.get().uri(uri -> uri.path("/v5/place/around")
                    .queryParam("key", properties.getWebKey())
                    .queryParam("location", coordinate(longitude) + "," + coordinate(latitude))
                    .queryParam("types", TYPES.get(category)).queryParam("radius", radius)
                    .queryParam("keywords", "{keyword}").queryParam("sortrule", "distance")
                    .queryParam("show_fields", "business,photos")
                    .queryParam("page_size", PAGE_SIZE).queryParam("page_num", page)
                    .build(keyword)).retrieve().body(JsonNode.class);
        } catch (Exception exception) {
            // Exceptions may contain the full upstream URL (and key); never log them here.
            log.warn("Nearby upstream transport failed, type={}, cause={}, httpStatus={}, elapsedMs={}",
                    exception.getClass().getSimpleName(),
                    exception.getCause() == null ? "none" : exception.getCause().getClass().getSimpleName(),
                    exception instanceof RestClientResponseException response ? response.getStatusCode().value() : "none",
                    elapsed(started));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "周边地点查询失败，请稍后重试");
        }
        if (body == null || !"1".equals(body.path("status").asText()) || !body.path("pois").isArray()) {
            String code = body == null ? "missing" : body.path("infocode").asText("");
            log.warn("Nearby upstream rejected, code={}, elapsedMs={}",
                    code.matches("\\d{5}") ? code : "invalid", elapsed(started));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "周边地点服务暂不可用，请稍后重试");
        }
        var items = new ArrayList<NearbyPage.Place>();
        JsonNode pois = body.path("pois");
        for (JsonNode poi : pois) {
            try {
                String id = text(poi, "id"), name = text(poi, "name");
                String[] location = text(poi, "location").split(",", -1);
                if (id.isBlank() || name.isBlank() || location.length != 2) continue;
                double x = Double.parseDouble(location[0]), y = Double.parseDouble(location[1]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 180 || Math.abs(y) > 90) continue;
                Double distance = null;
                try {
                    double value = Double.parseDouble(text(poi, "distance"));
                    if (Double.isFinite(value) && value >= 0) distance = value;
                } catch (NumberFormatException ignored) { /* Missing distance is optional. */ }
                items.add(new NearbyPage.Place(id, name, text(poi, "type"), text(poi, "typecode"),
                        text(poi, "address"), x, y, distance, rating(poi), photo(poi)));
            } catch (NumberFormatException ignored) { /* Skip malformed coordinates only. */ }
        }
        log.info("Nearby query completed, count={}, discarded={}, elapsedMs={}",
                items.size(), pois.size() - items.size(), elapsed(started));
        return new NearbyPage(items, page, PAGE_SIZE, page < 5 && pois.size() >= PAGE_SIZE);
    }

    private static Double rating(JsonNode poi) {
        JsonNode value = poi.path("business").path("rating");
        if (!value.isTextual() && !value.isNumber()) return null;
        try {
            double rating = Double.parseDouble(value.asText().strip());
            return Double.isFinite(rating) && rating >= 0 && rating <= 5 ? rating : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String photo(JsonNode poi) {
        JsonNode photos = poi.path("photos");
        if (!photos.isArray()) return "";
        for (JsonNode photo : photos) {
            String url = text(photo, "url");
            try {
                URI uri = URI.create(url);
                if ("http".equalsIgnoreCase(uri.getScheme())) url = "https:" + url.substring(url.indexOf(':') + 1);
                uri = URI.create(url);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null) return url;
            } catch (IllegalArgumentException ignored) { /* Skip unusable image links. */ }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private static String coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
