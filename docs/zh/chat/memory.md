# Memory 记忆
<div v-pre>


## 概述

`Memory` 模块是 Agents-Flex 框架中用于**管理对话上下文状态**的核心组件，支持从**内存存储**到**外部持久化**的灵活扩展。它与 `Prompt`（尤其是 `MemoryPrompt`）紧密配合，实现**多轮对话、上下文记忆、历史消息控制**等关键能力。

开发者可通过实现 `ChatMemory` 接口，将对话历史存储在 Redis、数据库、文件等任意后端，框架自动处理消息的加载与截断。


## 核心接口

### 1. `Memory`（标识接口）
```java
public interface Memory extends Serializable {
    Object id(); // 返回记忆的唯一标识（如 sessionId、userId）
}
```
- 所有记忆实现必须可序列化
- `id()` 用于区分不同会话/用户上下文

### 2. `ChatMemory`（对话记忆接口）
```java
public interface ChatMemory extends Memory {
    List<Message> getMessages();        // 获取当前所有消息
    void addMessage(Message message);   // 添加单条消息
    default void addMessages(Collection<Message> messages); // 批量添加
}
```
- **核心契约**：维护一个按时间顺序排列的 `Message` 列表
- 消息列表通常包含：`UserMessage` → `AiMessage` → `UserMessage` → `AiMessage` ...

---

## 内置实现：`DefaultChatMemory`

框架提供默认的**内存存储实现**，适用于单机会话或测试场景。

### 特性
- 基于 `ArrayList` 存储消息
- 自动分配 UUID 作为默认 ID
- 线程不安全（需外部同步或单线程使用）

### 使用示例
```java
// 自动生成 ID
ChatMemory memory = new DefaultChatMemory();

// 指定自定义 ID（如 sessionId）
ChatMemory memory = new DefaultChatMemory("session_123");

// 添加消息
memory.addMessage(new UserMessage("你好"));
memory.addMessage(new AiMessage("您好！有什么可以帮您？"));

// 获取历史
List<Message> history = memory.getMessages();
```

> ⚠️ **生产环境限制**：
> `DefaultChatMemory` **不支持持久化**，服务重启后数据丢失，仅适用于：
> - 单次任务型对话
> - 测试/演示环境
> - 与其他持久化逻辑配合使用


##  与 MemoryPrompt 集成

`MemoryPrompt` 是 `Memory` 的主要消费者，负责将记忆内容**动态构建为 LLM 输入**。

### 核心机制
```java
MemoryPrompt prompt = new MemoryPrompt(myChatMemory);
prompt.addMessage(new UserMessage("第一轮"));
prompt.addMessage(new AiMessage("第一轮回复"));

// 添加临时消息（如工具调用结果）
prompt.addMessageTemporary(new ToolMessage("执行成功"));

AiMessageResponse response = chatModel.chat(prompt);
```

### MemoryPrompt 关键配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| **`maxAttachedMessageCount`** | 10 | 最多携带多少条历史消息（不含 system） |
| **`historyMessageTruncateEnable`** | false | 是否启用历史消息截断 |
| **`historyMessageTruncateLength`** | 1000 | 每条消息最大字符数（超长则截断） |
| **`historyMessageTruncateProcessor`** | null | 自定义截断处理器（如摘要、关键词提取） |

### 工作流程（`getMessages()` 内部）
1. **截取最近 N 条消息**（通过 `maxAttachedMessageCount` 控制）
2. **消息内容截断**（若启用，基于副本操作，不修改原始消息）
3. **插入系统消息**（置于开头）
4. **追加临时消息**（使用后自动清空）

> ✅ **临时消息（Temporary Messages）**：
> 用于工具调用结果、中间推理等**一次性过程消息**，**不会存入 `ChatMemory`**，避免污染长期历史。


## 自定义 ChatMemory 实现（生产级）

### 实现步骤
1. 实现 `ChatMemory` 接口
2. 在 `id()` 中返回业务唯一标识（如 `userId + conversationId`）
3. 在 `getMessages()` / `addMessage()` 中对接持久化存储

### 示例：Redis 实现（伪代码）
```java
public class RedisChatMemory implements ChatMemory {
    private final String sessionId;
    private final RedisTemplate redis;

    public RedisChatMemory(String sessionId, RedisTemplate redis) {
        this.sessionId = sessionId;
        this.redis = redis;
    }

    @Override
    public Object id() {
        return sessionId;
    }

    @Override
    public List<Message> getMessages() {
        String json = redis.opsForValue().get("chat:memory:" + sessionId);
        return json != null ? JSON.parseArray(json, Message.class) : new ArrayList<>();
    }

    @Override
    public void addMessage(Message message) {
        List<Message> messages = getMessages();
        messages.add(message);
        redis.opsForValue().set("chat:memory:" + sessionId, JSON.toJSONString(messages));
    }
}
```

### 使用自定义 Memory
```java
ChatMemory memory = new RedisChatMemory("user_123_conv_456", redisTemplate);
MemoryPrompt prompt = new MemoryPrompt(memory);
// 后续操作与 DefaultChatMemory 完全一致
```


## 最佳实践

### 1. 控制上下文长度
- 设置合理的 `maxAttachedMessageCount`（建议 4~10 轮）
- 启用截断防止 token 超限：
  ```java
  prompt.setHistoryMessageTruncateEnable(true);
  prompt.setHistoryMessageTruncateLength(800);
  ```

### 2. 临时消息用于中间状态
- 工具调用结果、Agent 思考步骤等使用 `addMessageTemporary()`
- 避免将过程消息写入长期记忆

### 3. ID 设计规范
- 格式：`{业务域}:{userId}:{conversationId}`
- 示例：`"support:u1001:c2002"`

### 4. 消息清理策略
- 定期清理过期会话（如 7 天无活动）
- 对超长对话主动触发总结（Summarization）

### 5. 序列化兼容性
- 确保 `Message` 子类可正确序列化/反序列化
- 避免在消息中存储不可序列化对象

---

##  常见问题

**Q：临时消息和普通消息有什么区别？**

A：
- **普通消息**：通过 `addMessage()` 添加，**永久存入 `ChatMemory`**
- **临时消息**：通过 `addMessageTemporary()` 添加，**仅在本次 `getMessages()` 中出现，之后自动清除**

**Q：截断会修改原始消息吗？**

A：**不会**。`MemoryPrompt` 在截断时会创建消息副本，原始 `ChatMemory` 中的消息保持不变。

**Q：能否动态切换 Memory 实现？**

A：可以。`MemoryPrompt.setMemory()` 允许运行时替换记忆后端。






</div>
