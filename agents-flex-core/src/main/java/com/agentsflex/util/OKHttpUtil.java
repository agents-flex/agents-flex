/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.util;

import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OKHttpUtil {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    public static String post(String url, Map<String, String> headers, String payload) {
        return method(url, "POST", headers, payload);
    }

    public static String put(String url, Map<String, String> headers, String payload) {
        return method(url, "PUT", headers, payload);
    }

    public static String delete(String url, Map<String, String> headers, String payload) {
        return method(url, "DELETE", headers, payload);
    }

    private static String method(String url, String method, Map<String, String> headers, String payload) {
        Request.Builder builder = new Request.Builder()
            .url(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }

        RequestBody body = RequestBody.create(payload, JSON_TYPE);
        Request request = builder.method(method, body).build();

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build();

        try {
            Response response = client.newCall(request).execute();
            return response.message();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
