package com.cn.nearby;

import com.cn.config.MvcConfig;
import com.cn.utils.auth.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NearbyTest {
    private NearbyProperties properties;
    private MockRestServiceServer upstream;
    private AmapNearbyService service;

    @BeforeEach
    void setup() {
        properties = mock(NearbyProperties.class);
        when(properties.getWebKey()).thenReturn("test-secret");
        when(properties.getRateLimit()).thenReturn(30);
        when(properties.getTrustedProxies()).thenReturn("");
        var actualBuilder = RestClient.builder().baseUrl("https://restapi.amap.com");
        upstream = MockRestServiceServer.bindTo(actualBuilder).build();
        var builder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(actualBuilder.build());
        service = new AmapNearbyService(properties, builder);
    }

    @Test
    void mapsParametersAndCleansOptionalFieldsWithoutChangingMeters() {
        upstream.expect(queryParam("location", "113.123457,23.000000"))
                .andExpect(queryParam("types", "060000"))
                .andExpect(queryParam("radius", "3000"))
                .andExpect(queryParam("sortrule", "distance"))
                .andExpect(queryParam("page_size", "20"))
                .andExpect(queryParam("page_num", "2"))
                .andExpect(request -> assertTrue(request.getURI().getRawQuery().contains("%26")))
                .andRespond(withSuccess("""
                    {"status":"1","count":"999","pois":[
                      {"id":"B001","name":"书店","location":"113.1,23.1","address":[],"distance":"320"},
                      {"id":"B002","name":"小店","location":"113.2,23.2","distance":"NaN"},
                      {"id":"BAD","name":"坏坐标","location":"NaN,23"},
                      {"id":"","name":"无ID","location":"113,23"}]}
                    """, MediaType.APPLICATION_JSON));
        var page = service.search(113.1234567, 23, "shopping", 3000, "奶茶&蛋糕", 2);
        assertEquals(2, page.items().size());
        assertEquals("B001", page.items().get(0).poiId());
        assertEquals("", page.items().get(0).address());
        assertEquals(320.0, page.items().get(0).distanceMeters());
        assertNull(page.items().get(1).distanceMeters());
        assertFalse(page.hasMore()); // upstream count is not a total.
        upstream.verify();
    }

    @Test
    void optionalRatingsAndPhotosUseOneSearchAndHandleMissingOrInvalidValues() {
        upstream.expect(queryParam("show_fields", "business,photos"))
                .andRespond(withSuccess("""
                    {"status":"1","pois":[
                      {"id":"A","name":"餐馆","location":"113,23","business":{"rating":"4.8"},
                       "photos":[{"url":"javascript:alert(1)"},{"url":"https://example.com/a.jpg"}]},
                      {"id":"B","name":"小店","location":"113,23","business":{"rating":[]},"photos":[]},
                      {"id":"C","name":"另一家","location":"113,23","business":{"rating":"NaN"},
                       "photos":[{"url":"http://example.com/c.jpg"}]},
                      {"id":"D","name":"缺图","location":"113,23","business":{"rating":6},
                       "photos":[{"url":"https://user:pass@example.com/private"},{"url":"bad url"}]}]}
                    """, MediaType.APPLICATION_JSON));
        var places = service.search(113, 23, "food", 1000, "", 1).items();
        assertEquals(4, places.size());
        assertEquals(4.8, places.get(0).rating());
        assertEquals("https://example.com/a.jpg", places.get(0).photoUrl());
        assertNull(places.get(1).rating());
        assertEquals("", places.get(1).photoUrl());
        assertNull(places.get(2).rating());
        assertEquals("https://example.com/c.jpg", places.get(2).photoUrl());
        assertNull(places.get(3).rating());
        assertEquals("", places.get(3).photoUrl());
        upstream.verify(); // One upstream around search; no per-POI detail fan-out.
    }

    @Test
    void paginationUsesRawItemsAndStopsAtFive() {
        String body = "{\"status\":\"1\",\"pois\":[" + String.join(",", java.util.Collections.nCopies(20, "{}")) + "]}";
        upstream.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        upstream.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertTrue(service.search(113, 23, "food", 1000, "", 1).hasMore());
        assertFalse(service.search(113, 23, "food", 1000, "", 5).hasMore());
        upstream.verify();
    }

    @Test
    void transportBusinessAndMalformedFailuresAreSanitized() {
        upstream.expect(anything()).andRespond(withException(new IOException("url?key=test-secret")));
        upstream.expect(anything()).andRespond(withSuccess("{\"status\":\"0\",\"infocode\":\"10003\",\"info\":\"test-secret\"}", MediaType.APPLICATION_JSON));
        upstream.expect(anything()).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        upstream.expect(anything()).andRespond(withServerError());
        for (int i = 0; i < 4; i++) {
            var error = assertThrows(ResponseStatusException.class, () -> service.search(113, 23, "food", 1000, "", 1));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
            assertFalse(error.getReason().contains("test-secret"));
        }
        upstream.verify();
    }

    @Test
    void missingKeyAndEmptyResultsAreDistinct() {
        when(properties.getWebKey()).thenReturn("");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.search(113, 23, "food", 1000, "", 1)).getStatusCode());
        when(properties.getWebKey()).thenReturn("test-secret");
        upstream.expect(anything()).andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.search(113, 23, "food", 1000, "", 1).items().isEmpty());
        upstream.verify();
    }

    @Test
    void controllerRejectsInvalidInputsBeforeRateLimitOrUpstream() throws Exception {
        var limiter = mock(NearbyRateLimiter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NearbyController(service, limiter)).build();
        for (String query : List.of("longitude=NaN&latitude=23", "longitude=181&latitude=23",
                "longitude=113", "longitude=abc&latitude=23", "longitude=113&latitude=23&category=other",
                "longitude=113&latitude=23&radius=999", "longitude=113&latitude=23&page=0",
                "longitude=113&latitude=23&page=6")) {
            mvc.perform(get("/nearby/pois?" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        mvc.perform(get("/nearby/pois").param("longitude", "113").param("latitude", "23")
                .param("keyword", "字".repeat(81))).andExpect(status().isBadRequest());
        verifyNoInteractions(limiter);
        assertEquals("奶茶", AmapNearbyService.validate(113.0, 23.0, "food", 1000, "  奶茶  ", 1));
        upstream.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitFailsClosedAndOnlyTrustsConfiguredProxy() {
        var redis = mock(StringRedisTemplate.class);
        var limiter = new NearbyRateLimiter(redis, properties);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "192.0.2.5");
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(1L, 31L).thenThrow(new RuntimeException());
        limiter.check(request);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class, () -> limiter.check(request)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, () -> limiter.check(request)).getStatusCode());
        var keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, times(3)).execute(any(RedisScript.class), keys.capture());
        String directKey = keys.getAllValues().get(0).get(0).toString();
        when(properties.getTrustedProxies()).thenReturn("10.0.0.1");
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(1L);
        limiter.check(request);
        verify(redis, times(4)).execute(any(RedisScript.class), keys.capture());
        assertNotEquals(directKey, keys.getValue().get(0));
    }

    @Test
    void publicEndpointStillPassesThroughMvcInterceptors() throws Exception {
        // Exercise actual MvcConfig exclusion rather than merely testing standalone controller.
        var registry = new InterceptorRegistry() {
            public List<Object> handlers() { return getInterceptors(); }
        };
        var config = new MvcConfig();
        ReflectionTestUtils.setField(config, "jwtUtil", mock(JwtUtil.class));
        ReflectionTestUtils.setField(config, "stringRedisTemplate", mock(StringRedisTemplate.class));
        config.addInterceptors(registry);
        var limiter = mock(NearbyRateLimiter.class);
        var controller = new NearbyController(service, limiter);
        var mvcBuilder = MockMvcBuilders.standaloneSetup(controller);
        for (Object interceptor : registry.handlers()) {
            if (interceptor instanceof MappedInterceptor mapped) mvcBuilder.addMappedInterceptors(
                    mapped.getPathPatterns(), new org.springframework.web.servlet.HandlerInterceptor() {
                        @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                            return !mapped.matches(request) || mapped.getInterceptor().preHandle(request, response, handler);
                        }
                    });
        }
        upstream.expect(anything()).andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        mvcBuilder.build().perform(get("/nearby/pois").param("longitude", "113").param("latitude", "23"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(limiter).check(any());
    }
}
