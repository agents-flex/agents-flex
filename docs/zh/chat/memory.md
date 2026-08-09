<div v-pre>

# Memory 记忆

## 概述

`ChatMemory` 保存多轮对话的 `Message` 历史，`MemoryPrompt` 在每次调用模型前取出最近一部分消息并组装当前
Prompt。它解决的是“下一轮如何看到上一轮”，不是知识库，也不会自动从 ChatModel 响应中写入内容。

```text
业务加入 UserMessage
        ↓
ChatMemory 保存历史
        ↓
MemoryPrompt.getMessages(count)
        ↓ 加入 System 与临时消息
ChatModel
        ↓
业务把 AiMessage 写回 ChatMemory
```

## 适用场景

- 客服或助手的连续问答；
- 多步 Agent 需要保留前一步结果；
- 用户修改先前生成内容；
- 在多个服务实例间恢复会话历史；
- 对长对话保留最近消息并截断正文。

一次性问答使用 `SimplePrompt` 更直接；长期事实与企业知识应进入数据库、RAG 或 Wiki，不要只依赖聊天历史。

## 快速开始

```java
ChatMemory memory = new DefaultChatMemory("support:u1001:c2002");
MemoryPrompt prompt = new MemoryPrompt(memory);
prompt.setSystemMessage("你是订单客服助手");

prompt.addUserMessage("我的订单 1001 到哪里了？");
AiMessageResponse first = chatModel.chat(prompt);
prompt.addMessage(first.getMessage());

prompt.addUserMessage("预计什么时候送达？");
AiMessageResponse second = chatModel.chat(prompt);
prompt.addMessage(second.getMessage());
```

业务必须把每轮 UserMessage 和需要保留的 AiMessage 加入 Memory。示例的 UserMessage 通过
`prompt.addUserMessage()` 已经写入；模型响应需要显式 `addMessage()`。

## 核心接口

```java
public interface ChatMemory extends Memory {
    List<Message> getMessages(int count);
    void addMessage(Message message);
    void addMessages(Collection<? extends Message> messages);
    void clear();
}
```

`Memory.id()` 返回业务标识。`getMessages(count)` 必须返回最近最多 count 条按时间排序的消息；count 小于等于 0
时，内置实现会抛出 `IllegalArgumentException`。

## DefaultChatMemory

```java
ChatMemory generatedId = new DefaultChatMemory();
ChatMemory businessId = new DefaultChatMemory("tenant:u1001:c2002");

businessId.addMessage(new UserMessage("你好"));
List<Message> latest = businessId.getMessages(20);
businessId.clear();
```

当前实现使用普通 `ArrayList`：

- 无参构造生成 UUID；
- `getMessages()` 返回新的 List，修改返回 List 不会改变内部列表；
- Message 对象本身没有深复制；
- 不持久化，进程重启后丢失；
- 没有同步保护，不应由多个线程并发修改。

因此它适合测试、单机短会话和业务已经保证串行的场景。

## MemoryPrompt 如何组装历史

| 配置 | 当前默认值 | 说明 |
| --- | --- | --- |
| `maxAttachedMessageCount` | `100` | 每次附加最近多少条 Message |
| `historyMessageTruncateEnable` | `false` | 是否处理历史文本长度 |
| `historyMessageTruncateLength` | `1000` | 默认字符截断长度 |
| `historyMessageTruncateProcessor` | `null` | 自定义文本处理函数 |

```java
prompt.setMaxAttachedMessageCount(20);
prompt.setHistoryMessageTruncateEnable(true);
prompt.setHistoryMessageTruncateLength(1200);
```

启用截断后，MemoryPrompt 会复制 `AbstractTextMessage` 再修改内容，不改变 Memory 中的原消息。自定义 Processor
对每条文本消息调用，可以实现摘要或脱敏，但它同步运行在请求准备路径上，不应执行无界耗时操作。

计数单位是 Message。例如一轮 User + Ai 通常占两条，Tool Calling 还会增加 AiMessage 和 ToolMessage。

## 临时消息

临时消息参与下一次模型请求，但不写入 ChatMemory：

```java
ToolMessage toolMessage = new ToolMessage();
toolMessage.setToolCallId("call_123");
toolMessage.setContent("{\"temperature\":26}");

prompt.addMessageTemporary(toolMessage);
List<Message> requestMessages = prompt.getMessages();
```

`getMessages()` 返回后会自动清空临时列表。这适合一次性的 Tool 结果或过程上下文。调用
`clearTemporaryMessages()` 前应确认列表非空；当前实现对 null 列表直接调用会触发 NPE。

## 自定义持久化 Memory

生产环境通常按会话从 Redis 或数据库读取最近消息：

```java
public final class JdbcChatMemory implements ChatMemory {
    private final String conversationId;
    private final MessageRepository repository;

    public JdbcChatMemory(String conversationId, MessageRepository repository) {
        this.conversationId = conversationId;
        this.repository = repository;
    }

    @Override
    public Object id() {
        return conversationId;
    }

    @Override
    public List<Message> getMessages(int count) {
        return repository.findLatest(conversationId, count);
    }

    @Override
    public void addMessage(Message message) {
        repository.append(conversationId, message);
    }

    @Override
    public void clear() {
        repository.deleteConversation(conversationId);
    }
}
```

数据库查询如果按时间倒序取最近 N 条，返回给框架前必须恢复为从旧到新的协议顺序。

## 会话 ID 与租户隔离

推荐使用业务稳定且包含租户边界的 ID：

```text
support:<tenantId>:<accountId>:<conversationId>
```

ID 只是定位记录，不能替代授权。Repository 每次读写仍需校验当前租户和用户是否有权访问该会话，不要允许
客户端仅通过猜测 ID 读取其他用户历史。

## 上下文控制

`maxAttachedMessageCount` 只能限制条数，不能保证 Token 不超限。生产系统通常组合：

1. 保留最近若干完整消息；
2. 把更早历史总结成一条受控摘要；
3. 丢弃过大的 Tool 原始结果，只保存引用或关键字段；
4. 根据目标模型上下文窗口估算 Token；
5. 对会话设置过期、归档与删除策略。

摘要本身可能遗漏事实，应保留原始记录用于审计，并明确摘要版本与生成时间。

## 生产建议

- 同一 conversationId 的追加操作串行化或使用数据库顺序号；
- Message 序列化保留具体子类型、Tool Call ID 和必要 Usage 字段；
- 不长期保存无必要的推理内容、密钥和大段 Tool 输出；
- 对历史实施数据保留、用户删除和合规策略；
- 读取最近消息使用有界查询和索引，不要每轮加载全部历史再截取；
- ChatMemory 与 Runtime Workspace 是两套状态，业务需要用同一会话 ID 协调其生命周期。

## 常见问题

### DefaultChatMemory 线程安全吗？

不安全。它内部是普通 ArrayList，需要业务保证单会话串行或使用自定义并发实现。

### MemoryPrompt 会自动保存模型回复吗？

不会。业务取得响应后必须显式加入 Memory。

### 为什么默认附加 100 条，而不是 100 轮？

代码按 Message 计数。一次普通问答通常两条，Tool Calling 可能更多。

### 截断会修改数据库中的原始消息吗？

对于 `AbstractTextMessage`，MemoryPrompt 调用 `copy()` 后截断副本；自定义 Message 类型不参与这段文本截断逻辑。

## 下一步

- [构建 MemoryPrompt](./prompt)
- [理解 Message](./message)
- [使用对话上下文](./chat-context)
- [处理 Function Call](./function-call)

</div>
