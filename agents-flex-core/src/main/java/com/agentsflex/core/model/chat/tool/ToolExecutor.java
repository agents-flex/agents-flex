package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.message.ToolCall;

import java.util.*;

/**
 * 函数调用执行器，支持责任链拦截。
 * <p>
 * 执行顺序：全局拦截器 → 用户拦截器 → 实际函数调用。
 */
public class ToolExecutor {

    private final Tool tool;
    private final ToolCall toolCall;
    private List<ToolInterceptor> interceptors;

    public ToolExecutor(Tool tool, ToolCall toolCall) {
        this(tool, toolCall, null);
    }

    public ToolExecutor(Tool tool, ToolCall toolCall,
                        List<ToolInterceptor> userInterceptors) {
        this.tool = tool;
        this.toolCall = toolCall;
        this.interceptors = buildInterceptorChain(userInterceptors);
    }

    private List<ToolInterceptor> buildInterceptorChain(
        List<ToolInterceptor> userInterceptors) {

        // 1. 全局拦截器
        List<ToolInterceptor> chain = new ArrayList<>(GlobalToolInterceptors.getInterceptors());

        // 2. 用户拦截器
        if (userInterceptors != null) {
            chain.addAll(userInterceptors);
        }

        return chain;
    }

    /**
     * 动态添加拦截器（添加到链尾）
     */
    public void addInterceptor(ToolInterceptor interceptor) {
        if (interceptors == null) {
            interceptors = new ArrayList<>();
        }
        this.interceptors.add(interceptor);
    }

    public void addInterceptors(List<ToolInterceptor> interceptors) {
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
    public Object execute() {
        try (ToolContextHolder.ToolContextScope scope = ToolContextHolder.beginExecute(tool, toolCall)) {
            ToolChain chain = buildChain(0);
            return chain.proceed(scope.context);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new RuntimeException("Error invoking function: " + tool.getName(), e);
            }
        }
    }

    private ToolChain buildChain(int index) {
        if (index >= interceptors.size()) {
            return ctx -> ctx.getTool().invoke(ctx.getArgsMap());
        }

        ToolInterceptor current = interceptors.get(index);
        ToolChain next = buildChain(index + 1);
        return ctx -> current.intercept(ctx, next);
    }


    public Tool getTool() {
        return tool;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public List<ToolInterceptor> getInterceptors() {
        return interceptors;
    }

}
