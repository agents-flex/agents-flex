/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.event;

/**
 * Agent 执行过程中可以观察的事件类型。
 *
 * <p>事件由 {@code AgentRunner} 在对应动作发生后同步发布，仅用于日志、指标、实时界面和
 * 业务事件转发。事件名称描述的是已经发生的事实，监听器不能通过返回值改变后续执行。</p>
 */
public enum AgentEventType {
    /**
     * Turn 首次从 READY 状态进入执行流程，在该 Turn 的第一个 STEP_STARTED 之前发布。
     */
    TURN_STARTED,
    /**
     * Runner 开始推进一次 step；一次 step 最多发起一次模型调用。
     * data 中的 stepCount 是当前步骤的 1-based 编号，与对应 STEP_COMPLETED 保持一致。
     */
    STEP_STARTED,
    /**
     * 本次 step 已结束，随后可能继续下一 step，也可能阻塞或终止。
     *
     * <p>事件 data 包含执行后的 {@code status}、{@code executionPoint} 和本步骤产生的
     * {@code toolMessageCount}。如果本步骤使 Turn 终止，对应的 Turn 终止事件在本事件之后发布。</p>
     */
    STEP_COMPLETED,
    /**
     * 即将向当前 Agent 配置的 ChatModel 发起请求。
     */
    MODEL_STARTED,
    /**
     * 流式模型返回一段正文增量；完整响应不会以该事件重复发布。
     */
    MODEL_TEXT_DELTA,
    /**
     * 流式模型返回一段推理内容增量。
     */
    MODEL_REASONING_DELTA,
    /**
     * 流式模型返回一段 ToolCall 增量，data 中包含结构化 toolCalls。
     */
    MODEL_TOOL_CALL_DELTA,
    /**
     * 一次同步或流式模型调用已经得到完整响应。
     */
    MODEL_COMPLETED,
    /**
     * Runner 已为当前 Turn 发现可执行的上下文压缩流程，并即将判断和生成摘要。
     */
    CONTEXT_COMPRESSION_STARTED,
    /**
     * 上下文压缩已完成；data 中包含本次是否实际生成并保存了新摘要。
     */
    CONTEXT_COMPRESSION_COMPLETED,
    /**
     * 上下文压缩被跳过，通常表示没有新增消息或尚未达到触发条件。
     */
    CONTEXT_COMPRESSION_SKIPPED,
    /**
     * 上下文压缩失败；原始历史和旧摘要未被覆盖。
     */
    CONTEXT_COMPRESSION_FAILED,
    /**
     * Runner 即将执行一个已经解析并通过前置检查的工具调用。
     */
    TOOL_STARTED,
    /**
     * 长时间运行的工具通过 AgentToolProgressEmitter 主动上报进度。
     */
    TOOL_PROGRESS,
    /**
     * 工具成功返回，结果已经转换为后续模型可消费的 ToolMessage。
     */
    TOOL_COMPLETED,
    /**
     * 工具抛出异常；Turn 是否立即失败取决于 ToolErrorStrategy。
     */
    TOOL_FAILED,
    /**
     * 工具策略要求外部审批，Turn 已保存关联 ToolCall 并进入等待状态。
     */
    TOOL_APPROVAL_REQUESTED,
    /**
     * 业务工具在产生副作用前请求表单输入，原 ToolCall 已保留并等待用户提交。
     * data 包含 toolCallId、toolName 和 formKey；完整 Schema 保存在 Suspension。
     */
    TOOL_INPUT_REQUESTED,
    /**
     * ToolCall 已持久化并等待外部执行器执行。data 包含工具 ID、名称、参数和工具元数据。
     */
    EXTERNAL_TOOL_REQUESTED,
    /**
     * 外部执行器已成功返回工具结果。
     */
    EXTERNAL_TOOL_COMPLETED,
    /**
     * 外部执行器已返回结构化工具错误，错误将作为 ToolMessage 交给模型。
     */
    EXTERNAL_TOOL_FAILED,
    /**
     * AgentTurnSnapshot 已成功写入 AgentTurnStore，并获得新的版本号。
     */
    SNAPSHOT_SAVED,
    /**
     * Turn 已持久化为等待用户输入、工具审批或重试调度的状态。
     * 如果暂停由 Step 产生，本事件在该 Step 的 STEP_COMPLETED 之后发布。
     * data 包含 suspensionType、correlationId、message、resumeExecutionPoint 和 metadata。
     */
    TURN_SUSPENDED,
    /**
     * 外部恢复命令已经应用，Turn 可以继续推进。
     */
    TURN_RESUMED,
    /**
     * 外部系统已经写入协作式取消请求，但 Turn 可能尚未停止。
     */
    CANCELLATION_REQUESTED,
    /**
     * 可恢复异常已记录，Turn 正等待 nextRunnableAt 到达后重试。
     */
    RETRY_SCHEDULED,
    /**
     * 模型调用次数达到 maxIterations，Turn 已进入对应终止状态；在最终 STEP_COMPLETED 后发布。
     */
    MAX_ITERATIONS_REACHED,
    /**
     * Runner 推进次数达到 maxSteps，Turn 已进入对应终止状态；在最终 STEP_COMPLETED 后发布。
     */
    MAX_STEPS_REACHED,
    /**
     * 模型已返回不含待执行 ToolCall 的最终结果，Turn 正常结束；这是该 Turn 的最后一个生命周期事件。
     */
    TURN_COMPLETED,
    /**
     * Turn 因不可恢复的模型、工具或协议异常而失败，失败状态已经保存；在最终 STEP_COMPLETED 后发布。
     */
    TURN_FAILED,
    /**
     * Runner 已观察到取消请求、停止推进并将 Turn 标记为已取消；在最终 STEP_COMPLETED 后发布。
     */
    TURN_CANCELLED,
    /**
     * 时间、Token 或工具调用次数达到 AgentBudget 上限，Turn 已终止；在最终 STEP_COMPLETED 后发布。
     */
    BUDGET_EXCEEDED
}
