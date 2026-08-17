/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.ocr.mineru;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** OpenXLab HMAC 登录及内存 Token 缓存测试。 */
public class OpenXlabAuthClientTest {
    @Test
    public void shouldSignNonceAndCacheJwt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AgentsFlexHttpClient httpClient = new AgentsFlexHttpClient() {
            @Override
            public String post(String url, Map<String, String> headers, String payload) {
                int request = requests.incrementAndGet();
                JSONObject body = JSON.parseObject(payload);
                if (request == 1) {
                    assertEquals("ak-1", body.getString("ak"));
                    return success("{\"nonce\":\"nonce-1\",\"algorithm\":\"HmacSHA256\"}");
                }
                assertEquals("ak-1", body.getString("ak"));
                assertEquals(hmac("secret-1", "nonce-1"), body.getString("d"));
                return token("jwt-1", LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusDays(1));
            }
        };
        OpenXlabAuthClient client = new OpenXlabAuthClient(httpClient);

        assertEquals("jwt-1", client.getJwt("ak-1", "secret-1"));
        assertEquals("jwt-1", client.getJwt("ak-1", "secret-1"));
        assertEquals(2, requests.get());
    }

    @Test
    public void shouldRefreshExpiredJwtWithoutSigningAgain() {
        AtomicInteger requests = new AtomicInteger();
        AgentsFlexHttpClient httpClient = new AgentsFlexHttpClient() {
            @Override
            public String post(String url, Map<String, String> headers, String payload) {
                int request = requests.incrementAndGet();
                if (request == 1) return success("{\"nonce\":\"nonce-1\",\"algorithm\":\"HmacSHA256\"}");
                if (request == 2)
                    return token("expired-jwt", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1));
                JSONObject body = JSON.parseObject(payload);
                assertEquals("ak-1", body.getString("ak"));
                assertEquals("refresh-1", body.getString("refresh_token"));
                return token("refreshed-jwt", LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusDays(1));
            }
        };
        OpenXlabAuthClient client = new OpenXlabAuthClient(httpClient);

        assertEquals("expired-jwt", client.getJwt("ak-1", "secret-1"));
        assertEquals("refreshed-jwt", client.getJwt("ak-1", "secret-1"));
        assertEquals(3, requests.get());
    }

    @Test
    public void shouldDiscardCachedJwtWhenCredentialsChange() {
        AtomicInteger requests = new AtomicInteger();
        AgentsFlexHttpClient httpClient = new AgentsFlexHttpClient() {
            @Override
            public String post(String url, Map<String, String> headers, String payload) {
                int request = requests.incrementAndGet();
                JSONObject body = JSON.parseObject(payload);
                if (url.endsWith("/auth")) {
                    return success("{\"nonce\":\"nonce-" + body.getString("ak") +
                        "\",\"algorithm\":\"HmacSHA256\"}");
                }
                String accessKeyId = body.getString("ak");
                return token("jwt-" + accessKeyId, LocalDateTime.now().plusMinutes(10),
                    LocalDateTime.now().plusDays(1));
            }
        };
        OpenXlabAuthClient client = new OpenXlabAuthClient(httpClient);

        assertEquals("jwt-ak-1", client.getJwt("ak-1", "secret-1"));
        assertEquals("jwt-ak-2", client.getJwt("ak-2", "secret-2"));
        assertEquals(4, requests.get());
    }

    private static String success(String data) {
        return "{\"msgCode\":\"10000\",\"msg\":\"ok\",\"data\":{\"msgCode\":\"10000\"," +
            "\"msg\":\"ok\",\"data\":" + data + "}}";
    }

    private static String token(String jwt, LocalDateTime expiration, LocalDateTime refreshExpiration) {
        String data = "{\"jwt\":\"" + jwt + "\",\"refresh_token\":\"refresh-1\"," +
            "\"expiration\":\"" + format(expiration) + "\",\"refresh_expiration\":\"" +
            format(refreshExpiration) + "\"}";
        return success(data);
    }

    private static String format(LocalDateTime value) {
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
