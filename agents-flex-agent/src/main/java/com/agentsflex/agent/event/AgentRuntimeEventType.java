package com.agentsflex.agent.event;

/**
 * 进程内实时事件流使用的细粒度事件类型。
 */
public enum AgentRuntimeEventType {
    /**
     * Run 首次从 READY 进入执行状态。
     */
    RUN_STARTED,
    /**
     * Runner 开始推进一次 step。
     */
    STEP_STARTED,
    /**
     * Runner 完成一次 step。
     */
    STEP_COMPLETED,
    /**
     * 即将调用聊天模型。
     */
    MODEL_STARTED,
    /**
     * 模型产生一段增量文本。
     */
    MODEL_TEXT_DELTA,
    /**
     * 模型产生一段增量推理内容。
     */
    MODEL_REASONING_DELTA,
    /**
     * 模型产生一段增量 ToolCall 数据。
     */
    MODEL_TOOL_CALL_DELTA,
    /**
     * 一次模型调用已经返回完整响应。
     */
    MODEL_COMPLETED,
    /**
     * 即将执行一个业务工具。
     */
    TOOL_STARTED,
    /**
     * 长时间运行的工具主动上报进度。
     */
    TOOL_PROGRESS,
    /**
     * 业务工具已经成功返回。
     */
    TOOL_COMPLETED,
    /**
     * 业务工具执行抛出异常。
     */
    TOOL_FAILED,
    /**
     * 工具调用进入等待外部审批状态。
     */
    TOOL_APPROVAL_REQUESTED,
    /**
     * Run 状态已经保存到 AgentRunStore。
     */
    CHECKPOINT_SAVED,
    /**
     * Run 因外部条件未满足而暂停。
     */
    RUN_SUSPENDED,
    /**
     * 外部命令已经让 Run 恢复为可执行状态。
     */
    RUN_RESUMED,
    /**
     * 外部系统已经请求协作式取消。
     */
    CANCELLATION_REQUESTED,
    /**
     * 恢复命令已经写入持久化收件箱。
     */
    COMMAND_SUBMITTED,
    /**
     * Worker 已成功消费恢复命令。
     */
    COMMAND_CONSUMED,
    /**
     * Worker 多次消费恢复命令失败。
     */
    COMMAND_FAILED,
    /**
     * 上下文管理器压缩了持久化消息。
     */
    CONTEXT_COMPACTED,
    /**
     * 大型工具结果已经写入 Artifact Store。
     */
    TOOL_RESULT_OFFLOADED,
    /**
     * 父 Run 已创建并关联一个子 Run。
     */
    CHILD_STARTED,
    /**
     * 模型创建了初始任务计划。
     */
    PLAN_CREATED,
    /**
     * 模型调整了尚未执行的任务。
     */
    PLAN_UPDATED,
    /**
     * 一个计划任务已经关联子 Run 并开始执行。
     */
    TASK_STARTED,
    /**
     * 一个计划任务成功完成。
     */
    TASK_COMPLETED,
    /**
     * 一个计划任务失败或取消。
     */
    TASK_FAILED,
    /**
     * Run 已安排下一次自动重试。
     */
    RETRY_SCHEDULED,
    /**
     * 模型调用次数达到强制上限。
     */
    MAX_ITERATIONS_REACHED,
    /**
     * 执行模式推进次数达到强制上限。
     */
    MAX_STEPS_REACHED,
    /**
     * Run 正常完成。
     */
    RUN_COMPLETED,
    /**
     * Run 因不可恢复异常失败。
     */
    RUN_FAILED,
    /**
     * Run 已响应协作式取消。
     */
    RUN_CANCELLED,
    /**
     * Run 因时间、Token 或工具调用预算终止。
     */
    BUDGET_EXCEEDED
}
