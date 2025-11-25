# Chat 日志
<div v-pre>


## 概述

Agents-Flex 提供了**统一、可插拔的请求日志系统**，用于记录所有与大语言模型（LLM）服务的交互细节，包括：

- **请求日志**：发送给 LLM 的原始请求体（JSON）
- **响应日志**：LLM 返回的原始响应体（JSON）

该系统设计目标：

- ✅ **开箱即用**：默认输出到 `System.out`
- ✅ **灵活替换**：支持自定义日志实现（如 SLF4J、Log4j、文件、数据库等）
- ✅ **按需启用**：通过 `ChatConfig.isLogEnabled()` 控制开关
- ✅ **上下文丰富**：自动附加 `provider/model` 信息，便于排查


## 核心组件

###  `IChatMessageLogger` 接口

```java
public interface IChatMessageLogger {
    void logRequest(ChatConfig config, String message);
    void logResponse(ChatConfig config, String message);
}
```

- **职责**：定义日志记录的契约
- **参数**：
    - `config`：模型配置（含 provider、model、logEnabled 等）
    - `message`：原始 JSON 字符串（请求体或响应体）

> 📌 所有日志实现必须实现此接口。


### `ChatMessageLogger` 全局门面

```java
public final class ChatMessageLogger {
    private static IChatMessageLogger logger = new DefaultChatMessageLogger();

    public static void setLogger(IChatMessageLogger logger) { /* ... */ }
    public static void logRequest(ChatConfig config, String message) { /* ... */ }
    public static void logResponse(ChatConfig config, String message) { /* ... */ }
}
```

- **单例模式**：全局唯一日志器实例
- **线程安全**：`setLogger()` 是线程安全的（适合应用启动时初始化）

> ✅ **调用方式**：所有内部组件通过 `ChatMessageLogger.logRequest(...)` 记录日志。


### `DefaultChatMessageLogger` 默认实现

```java
public class DefaultChatMessageLogger implements IChatMessageLogger {
    private final Consumer<String> logConsumer;

    public DefaultChatMessageLogger(Consumer<String> logConsumer) { /* ... */ }

    @Override
    public void logRequest(ChatConfig config, String message) {
        if (shouldLog(config)) {
            String provider = getProviderName(config);
            String model = getModelName(config);
            logConsumer.accept(String.format("[%s/%s] >>>> request: %s", provider, model, message));
        }
    }

    @Override
    public void logResponse(ChatConfig config, String message) {
        if (shouldLog(config)) {
            String provider = getProviderName(config);
            String model = getModelName(config);
            logConsumer.accept(String.format("[%s/%s] <<<< response: %s", provider, model, message));
        }
    }
}
```

- **默认行为**：输出到 `System.out`
- **格式**：`[provider/model] >>>> request: {...}` / `<<<< response: {...}`
- **条件记录**：仅当 `config.isLogEnabled() == true` 时记录
- **可定制输出**：通过 `Consumer<String>` 替换输出目标



## 自定义日志实现

### 使用 SLF4J 记录到日志框架

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jChatMessageLogger implements IChatMessageLogger {
    private static final Logger logger = LoggerFactory.getLogger("LLM.Chat");

    @Override
    public void logRequest(ChatConfig config, String message) {
        if (logger.isDebugEnabled() && shouldLog(config)) {
            logger.debug("[{}/{}] >>>> request: {}",
                getProvider(config), getModel(config), maskSensitiveData(message));
        }
    }

    @Override
    public void logResponse(ChatConfig config, String message) {
        if (logger.isDebugEnabled() && shouldLog(config)) {
            logger.debug("[{}/{}] <<<< response: {}",
                getProvider(config), getModel(config), message);
        }
    }

    private boolean shouldLog(ChatConfig config) {
        return config != null && config.isLogEnabled();
    }

    private String maskSensitiveData(String json) {
        // 掩码 API Key 等敏感字段（示例）
        return json.replaceAll("(\"api_key\"\\s*:\\s*\")[^\"]+\"", "$1***\"");
    }
}

// 注册到全局
ChatMessageLogger.setLogger(new Slf4jChatMessageLogger());
```

### 记录到文件

```java
public class FileChatMessageLogger implements IChatMessageLogger {
    private final PrintWriter writer;

    public FileChatMessageLogger(String filePath) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filePath, true));
    }

    @Override
    public void logRequest(ChatConfig config, String message) {
        if (shouldLog(config)) {
            writer.println(new Date() + " [REQUEST] " + message);
            writer.flush();
        }
    }

    @Override
    public void logResponse(ChatConfig config, String message) {
        if (shouldLog(config)) {
            writer.println(new Date() + " [RESPONSE] " + message);
            writer.flush();
        }
    }

    // 实现 shouldLog/getProvider/getModel...
}
```

##  配置与控制

### 启用/禁用日志

通过 `ChatConfig` 控制：

```java
OpenAIConfig config = new OpenAIConfig();
config.setLogEnabled(true);  // 默认为 true
// 或
config.setLogEnabled(false); // 完全关闭日志
```

> 🔒 **生产建议**：生产环境默认关闭，调试时开启。

### 日志内容脱敏

**重要**：请求/响应中可能包含敏感信息（如 API Key、用户隐私），建议在自定义 logger 中实现脱敏：

- 移除 `Authorization` 头（但请求体中通常不含）
- 掩码用户输入中的 PII（个人身份信息）
- 避免记录完整上下文（如长对话历史）


## 日志格式说明

### 同步请求示例

```
[openai/gpt-4o] >>>> request: {"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}],"stream":false}
[openai/gpt-4o] <<<< response: {"choices":[{"message":{"content":"Hi there!","role":"assistant"}}],"usage":{"total_tokens":10}}
```

### 流式请求示例

```
[openai/gpt-4o] >>>> request: {"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}],"stream":true}
[openai/gpt-4o] <<<< response: {"choices":[{"delta":{"role":"assistant"},"index":0}]}
[openai/gpt-4o] <<<< response: {"choices":[{"delta":{"content":"Hi"},"index":0}]}
[openai/gpt-4o] <<<< response: {"choices":[{"delta":{"content":" there!"},"index":0}]}
[openai/gpt-4o] <<<< response: {"choices":[{"finish_reason":"stop","index":0}]}
```

## 最佳实践

### ✅ 推荐做法
- **开发/测试环境**：启用日志，便于调试
- **生产环境**：默认关闭，按需开启（如排查问题）
- **自定义 logger**：集成到现有日志体系（如 SLF4J + Logback）
- **敏感数据处理**：务必脱敏后再记录

### ❌ 避免事项
- 不要将原始日志直接暴露给前端或日志聚合系统（未脱敏）
- 不要长期开启全量日志（可能产生海量数据）
- 不要依赖日志作为审计主通道（应使用专门的审计日志）



## 总结

Agents-Flex 的请求日志系统：

- **简单易用**：默认输出到控制台，一行代码切换实现
- **灵活可控**：按模型/请求级别开关，支持任意输出目标
- **上下文丰富**：自动标注 provider/model，提升可读性
- **安全第一**：提供脱敏扩展点，保护敏感数据

> 📘 **建议**：在开发阶段始终开启日志，在生产环境通过配置动态控制。




</div>
