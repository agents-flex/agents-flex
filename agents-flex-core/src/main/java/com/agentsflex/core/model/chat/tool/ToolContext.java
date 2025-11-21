package com.agentsflex.core.model.chat.tool;


import com.agentsflex.core.message.ToolCall;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 函数调用上下文，贯穿整个拦截链。
 */
public class ToolContext implements Serializable {
    private final Tool tool;
    private final ToolCall toolCall;
    private final Map<String, Object> attributes = new HashMap<>();

    private Object result;
    private Throwable throwable;


    public ToolContext(Tool tool, ToolCall toolCall) {
        this.tool = tool;
        this.toolCall = toolCall;
    }

    public Tool getTool() {
        return tool;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public Map<String, Object> getArgsMap() {
        return toolCall.getArgsMap();
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
