package com.agentsflex.core.model.chat.functions;


import com.agentsflex.core.message.FunctionCall;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 函数调用上下文，贯穿整个拦截链。
 */
public class FunctionContext implements Serializable {
    private final Function function;
    private final FunctionCall functionCall;
    private final Map<String, Object> attributes = new HashMap<>();

    private Object result;
    private Throwable throwable;


    public FunctionContext(Function function, FunctionCall functionCall) {
        this.function = function;
        this.functionCall = functionCall;
    }

    public Function getFunction() {
        return function;
    }

    public FunctionCall getFunctionCall() {
        return functionCall;
    }

    public Map<String, Object> getArgsMap() {
        return functionCall.getArgsMap();
    }


    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }


    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public boolean hasException() {
        return throwable != null;
    }
}
