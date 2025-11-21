package com.agentsflex.core.model.chat.functions;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全局函数调用拦截器注册中心。
 * <p>
 * 所有通过 {@link #addInterceptor(FunctionInterceptor)} 注册的拦截器，
 * 将自动应用于所有 {@link com.agentsflex.core.model.chat.functions.FunctionInvoker} 实例。
 */
public final class GlobalFunctionInterceptors {

    private static final List<FunctionInterceptor> GLOBAL_INTERCEPTORS = new ArrayList<>();

    private GlobalFunctionInterceptors() {
    }

    public static void addInterceptor(FunctionInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("Interceptor must not be null");
        }
        GLOBAL_INTERCEPTORS.add(interceptor);
    }

    public static List<FunctionInterceptor> getInterceptors() {
        return Collections.unmodifiableList(GLOBAL_INTERCEPTORS);
    }

    public static void clear() {
        GLOBAL_INTERCEPTORS.clear();
    }
}
