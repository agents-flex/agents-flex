/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.client.impl;

import com.agentsflex.core.model.exception.ModelErrorClassifier;
import com.agentsflex.core.util.StringUtil;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

class Util {

    public static Throwable getFailureThrowable(Throwable t, Response response) {
        if (t != null) {
            return t;
        }

        if (response != null) {
            String errMessage = "Response code: " + response.code();
            String message = response.message();
            if (StringUtil.hasText(message)) {
                errMessage += ", message: " + message;
            }
            try (ResponseBody body = response.body()) {
                if (body != null) {
                    String string = body.string();
                    if (StringUtil.hasText(string)) {
                        errMessage += ", body: " + string;
                        return ModelErrorClassifier.fromHttpError(response.code(), string,
                            retryAfterMillis(response.header("Retry-After")));
                    }
                }
            } catch (IOException e) {
                // ignore
            }
            t = ModelErrorClassifier.fromHttpError(response.code(), errMessage,
                retryAfterMillis(response.header("Retry-After")));
        }

        return t;

    }

    private static Long retryAfterMillis(String value) {
        if (!StringUtil.hasText(value)) return null;
        try {
            return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
