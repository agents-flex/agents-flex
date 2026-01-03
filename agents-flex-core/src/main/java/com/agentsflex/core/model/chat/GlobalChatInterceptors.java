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

package com.agentsflex.core.model.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全局聊天拦截器管理器。
 * <p>
 * 该类提供静态方法，用于注册和管理应用于所有 {@link BaseChatModel} 实例的全局拦截器。
 * 全局拦截器会在实例级拦截器之前执行，适用于统一的日志、安全、监控等横切关注点。
 * <p>
 * <strong>使用建议</strong>：
 * <ul>
 *   <li>在应用启动阶段（如 Spring 的 {@code @PostConstruct} 或 main 方法）注册全局拦截器</li>
 *   <li>避免在运行时动态修改，以确保线程安全和行为一致性</li>
 * </ul>
 */
public final class GlobalChatInterceptors {

    /**
     * 全局拦截器列表，使用 synchronized 保证线程安全
     */
    private static final List<ChatInterceptor> GLOBAL_INTERCEPTORS = new ArrayList<>();

    /**
     * 私有构造函数，防止实例化
     */
    private GlobalChatInterceptors() {
        // 工具类，禁止实例化
    }

    /**
     * 注册一个全局拦截器。
     * <p>
     * 该拦截器将应用于所有后续创建的 {@link BaseChatModel} 实例。
     *
     * @param interceptor 要注册的拦截器，不能为 null
     * @throws IllegalArgumentException 如果 interceptor 为 null
     */
    public static synchronized void addInterceptor(ChatInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("ChatInterceptor must not be null");
        }
        GLOBAL_INTERCEPTORS.add(interceptor);
    }

    /**
     * 批量注册多个全局拦截器。
     * <p>
     * 拦截器将按列表顺序添加，并在执行时按相同顺序调用。
     *
     * @param interceptors 拦截器列表，不能为 null；列表中元素不能为 null
     * @throws IllegalArgumentException 如果 interceptors 为 null 或包含 null 元素
     */
    public static synchronized void addInterceptors(List<ChatInterceptor> interceptors) {
        if (interceptors == null) {
            throw new IllegalArgumentException("Interceptor list must not be null");
        }
        for (ChatInterceptor interceptor : interceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("Interceptor list must not contain null elements");
            }
        }
        GLOBAL_INTERCEPTORS.addAll(interceptors);
    }

    /**
     * 获取当前注册的全局拦截器列表的不可变视图。
     * <p>
     * 该方法供 {@link BaseChatModel} 内部使用，返回值不应被外部修改。
     *
     * @return 不可变的全局拦截器列表
     */
    public static List<ChatInterceptor> getInterceptors() {
        return Collections.unmodifiableList(GLOBAL_INTERCEPTORS);
    }

    /**
     * 清空所有全局拦截器。
     * <p>
     * <strong>仅用于测试环境</strong>，生产环境应避免调用。
     */
    public static synchronized void clear() {
        GLOBAL_INTERCEPTORS.clear();
    }

    /**
     * 获取当前全局拦截器的数量。
     * <p>
     * 用于诊断或监控。
     *
     * @return 拦截器数量
     */
    public static synchronized int size() {
        return GLOBAL_INTERCEPTORS.size();
    }
}
