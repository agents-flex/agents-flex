/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

/** 根据命令行参数运行一个或全部 Agent 学习场景。 */
public final class AgentDemoLauncher {

    private AgentDemoLauncher() {
    }

    public static void main(String[] args) {
        // 不传参数时按学习顺序运行全部场景；传参数时只运行对应的独立场景。
        String scenario = args.length == 0 ? "all" : args[0];
        if ("all".equalsIgnoreCase(scenario) || "tool".equalsIgnoreCase(scenario)) {
            ToolCallingAgentDemo.run();
        }
        if ("all".equalsIgnoreCase(scenario) || "approval".equalsIgnoreCase(scenario)) {
            HumanApprovalAgentDemo.run();
        }
        if ("all".equalsIgnoreCase(scenario) || "worker".equalsIgnoreCase(scenario)) {
            DurableWorkerAgentDemo.run();
        }
        if ("all".equalsIgnoreCase(scenario) || "runtime".equalsIgnoreCase(scenario)) {
            RuntimeExtensionsAgentDemo.run();
        }
        if (!"all".equalsIgnoreCase(scenario)
            && !"tool".equalsIgnoreCase(scenario)
            && !"approval".equalsIgnoreCase(scenario)
            && !"worker".equalsIgnoreCase(scenario)
            && !"runtime".equalsIgnoreCase(scenario)) {
            throw new IllegalArgumentException(
                "未知场景: " + scenario
                    + "，可选值为 tool、approval、worker、runtime");
        }
    }
}
