/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.ocr.mineru;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;

/** OpenXLab AK/SK 登录、JWT 缓存及刷新客户端。 */
class OpenXlabAuthClient {
    static final String AUTH_ENDPOINT = "https://openapi.openxlab.org.cn/api/v1/sso-be/api/v1/open/";
    private static final DateTimeFormatter EXPIRATION_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long EXPIRATION_SKEW_MILLIS = 30_000L;

    private final AgentsFlexHttpClient httpClient;
    private volatile Token token;
    private String tokenAccessKeyId;
    private String tokenSecretAccessKey;

    OpenXlabAuthClient(AgentsFlexHttpClient httpClient) {
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    synchronized String getJwt(String accessKeyId, String secretAccessKey) {
        if (!accessKeyId.equals(tokenAccessKeyId) || !secretAccessKey.equals(tokenSecretAccessKey)) {
            token = null;
            tokenAccessKeyId = accessKeyId;
            tokenSecretAccessKey = secretAccessKey;
        }
        long now = System.currentTimeMillis();
        if (token != null && token.expiresAtMillis - EXPIRATION_SKEW_MILLIS > now) return token.jwt;
        if (token != null && StringUtil.hasText(token.refreshToken) &&
            token.refreshExpiresAtMillis - EXPIRATION_SKEW_MILLIS > now) {
            try {
                token = requestToken("refreshJwt", json("ak", accessKeyId, "refresh_token", token.refreshToken));
                return token.jwt;
            } catch (RuntimeException ignored) {
                token = null;
            }
        }
        JSONObject auth = requestData("auth", json("ak", accessKeyId));
        String nonce = auth.getString("nonce");
        String algorithm = auth.getString("algorithm");
        String signature = sign(secretAccessKey, nonce, algorithm);
        token = requestToken("getJwt", json("ak", accessKeyId, "d", signature));
        return token.jwt;
    }

    private Token requestToken(String path, JSONObject payload) {
        JSONObject data = requestData(path, payload);
        String jwt = data.getString("jwt");
        if (StringUtil.noText(jwt)) throw new IllegalStateException("OpenXLab response does not contain jwt");
        return new Token(jwt, data.getString("refresh_token"), parseExpiration(data.getString("expiration")),
            parseExpiration(data.getString("refresh_expiration")));
    }

    private JSONObject requestData(String path, JSONObject payload) {
        String response = httpClient.post(AUTH_ENDPOINT + path, Collections.singletonMap("Content-Type", "application/json"),
            payload.toJSONString());
        JSONObject root;
        try {
            root = JSON.parseObject(response);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid OpenXLab authentication response", e);
        }
        if (!"10000".equals(root.getString("msgCode")))
            throw new IllegalStateException("OpenXLab authentication failed: " + root.getString("msg"));
        JSONObject wrapper = root.getJSONObject("data");
        JSONObject data = wrapper == null ? null : wrapper.getJSONObject("data");
        if (data == null || !"10000".equals(wrapper.getString("msgCode")))
            throw new IllegalStateException("OpenXLab authentication failed: " +
                (wrapper == null ? "response data is empty" : wrapper.getString("msg")));
        return data;
    }

    private static JSONObject json(String... values) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < values.length; i += 2) object.put(values[i], values[i + 1]);
        return object;
    }

    private static String sign(String secret, String nonce, String algorithm) {
        if (StringUtil.noText(nonce) || StringUtil.noText(algorithm))
            throw new IllegalStateException("OpenXLab authentication response is missing nonce or algorithm");
        String javaAlgorithm = algorithm.startsWith("Hmac") ? algorithm : "Hmac" + algorithm;
        try {
            Mac mac = Mac.getInstance(javaAlgorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), javaAlgorithm));
            return Base64.getEncoder().encodeToString(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unsupported OpenXLab HMAC algorithm: " + algorithm, e);
        }
    }

    private static long parseExpiration(String value) {
        if (StringUtil.noText(value)) return 0L;
        try {
            return LocalDateTime.parse(value, EXPIRATION_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static class Token {
        private final String jwt;
        private final String refreshToken;
        private final long expiresAtMillis;
        private final long refreshExpiresAtMillis;

        private Token(String jwt, String refreshToken, long expiresAtMillis, long refreshExpiresAtMillis) {
            this.jwt = jwt;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
            this.refreshExpiresAtMillis = refreshExpiresAtMillis;
        }
    }
}
