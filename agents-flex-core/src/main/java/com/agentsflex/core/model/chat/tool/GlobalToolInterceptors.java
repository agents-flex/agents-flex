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
