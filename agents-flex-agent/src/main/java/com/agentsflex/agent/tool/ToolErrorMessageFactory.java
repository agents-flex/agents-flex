/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将工具调用异常转换为提供给模型的 {@link ToolMessage}。
 *
 * <p>仅当 {@link ToolErrorStrategy#RETURN_ERROR_TO_MODEL} 生效时调用。实现可按业务需要隐藏内部
 * 异常、映射错误码或提供模型可执行的修复建议；Runner 会始终以原始 ToolCall ID 覆盖返回消息的
 * {@code toolCallId}，保证工具调用协议完整。</p>
 */
@FunctionalInterface
public interface ToolErrorMessageFactory {

    /**
     * 使用框架默认的结构化错误格式。
     */
    ToolErrorMessageFactory DEFAULT = (turn, call, error) -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("type", "tool_execution_error");
        body.put("message", error == null ? null : error.getMessage());
        ToolMessage result = new ToolMessage();
        result.setContent(JSON.toJSONString(body));
        return result;
    };

    /**
     * 根据当前 Turn、原始 ToolCall 和异常生成模型可见的工具结果。
     *
     * @return 不得返回 {@code null}
     */
    ToolMessage create(AgentTurn turn, ToolCall call, Throwable error);

    /**
     * @return 框架默认的结构化错误消息工厂
     */
    static ToolErrorMessageFactory defaultFactory() {
        return DEFAULT;
    }
}
