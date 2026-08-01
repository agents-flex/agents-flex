/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;

import java.util.Arrays;

/** Demo 输出和运行结果校验工具。 */
final class DemoSupport {

    private DemoSupport() {
    }

    static AiMessage toolCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(title);
        System.out.println("============================================================");
    }

    static void printRun(AgentRun run) {
        System.out.println("runId       : " + run.getId());
        System.out.println("status      : " + run.getStatus());
        System.out.println("phase       : " + run.getPhase());
        System.out.println("iterations  : " + run.getIterationCount());
        System.out.println("toolCalls   : " + run.getToolCallCount());
        System.out.println("finalOutput : " + run.getFinalOutput());
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Demo 校验失败: " + message);
        }
    }
}
