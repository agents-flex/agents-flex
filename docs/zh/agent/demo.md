# Agents-flex 的 Agent Demo 示例

一个连接真实 OpenAI-compatible 大模型的全链路 Agent Demo。中央区域是由真实 `ChatMemory` 驱动的持续 AI 对话框，每轮创建独立 `AgentTurn` 并复用同一会话上下文；正文和思考过程通过原生 SSE 增量事件流式显示。

## 开源地址
开源地址：https://gitee.com/agents-flex/agents-flex-demo


## 能力展示

| 能力 | 实现来源 | Showcase 场景 |
| --- | --- | --- |
| Event | Agents-Flex Native | SSE 实时转发全部 `AgentEvent`；Token Delta 按帧批量更新，右侧通过虚拟滚动展示完整历史 |
| Form Input | Agents-Flex Native | `AgentUserInputTool` 固化 JSON Schema，前端动态渲染 |
| Human Approval | Agents-Flex Native | 发布报告前由 `ToolApprovalPolicy` 挂起原 Turn |
| Message Compression | Agents-Flex Native | 可选择消息数、Turn 数、Token 数、始终或从不触发，并支持整段历史与逐消息两种模型压缩模式 |
| Suspend / Resume | Agents-Flex Native + Demo Control | READY 可立即挂起；运行中请求会在当前原子 Step 完成后的安全检查点生效 |
| Budget Control | Agents-Flex Native | 输入、输出、总 Token、工具次数和墙钟时长由用户创建 Agent 时配置，并展示原生超限原因 |
| Retry | Agents-Flex Native | 来源校验前两次失败，由 `AgentRetryPolicy` 和 `AgentWorker` 恢复同一 ToolCall，同时展示间隔和退避策略 |
| Trace + Metrics | Agents-Flex OpenTelemetry | 每个 Run 使用独立 `TelemetryRoute`，展示真实 Chat/Tool Span、Token、内容和原生 Metrics；生命周期事件单独展示 |
| Cost | Demo Projection | 金额仅按 Token 做展示性估算，标记为 `DEMO_PROJECTION` |

生产运行通过 `agents-flex-chat-openai` 调用真实模型，主对话和上下文摘要都进入 Agents-Flex OpenTelemetry Chat 拦截器链。确定性模型只用于自动化测试；Agent 的状态迁移、快照、工具执行、暂停、恢复、审批、预算、重试、压缩和事件均由框架执行。



## 示例截图

![](./agent-flex-demo.png)



