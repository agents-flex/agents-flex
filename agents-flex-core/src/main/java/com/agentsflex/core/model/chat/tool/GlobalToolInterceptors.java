/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.chat.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全局函数调用拦截器注册中心。
 * <p>
 * 所有通过 {@link #addInterceptor(ToolInterceptor)} 注册的拦截器，
 * 将自动应用于所有 {@link ToolExecutor} 实例。
 */
public final class GlobalToolInterceptors {

    private static final List<ToolInterceptor> GLOBAL_INTERCEPTORS = new ArrayList<>();

    private GlobalToolInterceptors() {
    }

    public static void addInterceptor(ToolInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("Interceptor must not be null");
        }
        GLOBAL_INTERCEPTORS.add(interceptor);
    }

    public static List<ToolInterceptor> getInterceptors() {
        return Collections.unmodifiableList(GLOBAL_INTERCEPTORS);
    }

    public static void clear() {
        GLOBAL_INTERCEPTORS.clear();
    }
}
