package com.agentsflex.core.model.chat.functions;

import com.agentsflex.core.message.FunctionCall;

import java.util.*;

/**
 * 函数调用执行器，支持责任链拦截。
 * <p>
 * 执行顺序：全局拦截器 → 用户拦截器 → 实际函数调用。
 */
public class FunctionInvoker {

    private final Function function;
    private final FunctionCall functionCall;
    private final FunctionContext context;
    private List<FunctionInterceptor> interceptors;

    public FunctionInvoker(Function function, FunctionCall functionCall) {
        this(function, functionCall, null);
    }

    public FunctionInvoker(Function function, FunctionCall functionCall,
                           List<FunctionInterceptor> userInterceptors) {
        this.function = function;
        this.functionCall = functionCall;
        this.context = new FunctionContext(function, functionCall);
        this.interceptors = buildInterceptorChain(userInterceptors);
    }

    private List<FunctionInterceptor> buildInterceptorChain(
        List<FunctionInterceptor> userInterceptors) {
        List<FunctionInterceptor> chain = new ArrayList<>();

        // 1. 全局拦截器
        chain.addAll(GlobalFunctionInterceptors.getInterceptors());

        // 2. 用户拦截器
        if (userInterceptors != null) {
            chain.addAll(userInterceptors);
        }

        return chain;
    }

    /**
     * 动态添加拦截器（添加到链尾）
     */
    public void addInterceptor(FunctionInterceptor interceptor) {
        if (interceptors == null) {
            interceptors = new ArrayList<>();
        }
        this.interceptors.add(interceptor);
    }

    public void addInterceptors(List<FunctionInterceptor> interceptors) {
        if (interceptors == null) {
            interceptors = new ArrayList<>();
        }
        this.interceptors.addAll(interceptors);
    }

    /**
     * 执行函数调用，触发拦截链。
     *
     * @return 函数返回值
     * @throws RuntimeException 包装原始异常
     */
    public Object invoke() {
        try {
            FunctionChain chain = buildChain(0);
            Object result = chain.proceed(context);
            context.setResult(result);
            return result;
        } catch (Exception e) {
            context.setThrowable(e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new RuntimeException("Error invoking function: " + function.getName(), e);
            }
        }
    }

    private FunctionChain buildChain(int index) {
        if (index >= interceptors.size()) {
            return ctx -> ctx.getFunction().invoke(ctx.getArgsMap());
        }

        FunctionInterceptor current = interceptors.get(index);
        FunctionChain next = buildChain(index + 1);
        return ctx -> current.intercept(ctx, next);
    }

    // ——— 辅助方法 ———

    public Function getFunction() {
        return function;
    }

    public FunctionCall getFunctionCall() {
        return functionCall;
    }

    public FunctionContext getContext() {
        return context;
    }
}
