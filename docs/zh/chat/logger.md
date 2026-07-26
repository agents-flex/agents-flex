---
title: Chat 日志
description: 控制模型原始请求与响应日志，并接入现有日志系统和敏感数据治理。
---

# Chat 日志

<div v-pre>

## 概述

Chat 日志记录发送给模型的最终请求 Body 和同步原始响应，帮助排查序列化、参数和服务返回问题。默认实现输出到 `System.out`，格式包含 Config 中的 provider/model。

它是调试日志，不是指标、Trace 或合规审计系统。生产调用链观测应结合 Observability 和业务审计。

## 适用场景

- 开发阶段检查 messages、tools 和模型参数是否正确。
- 排查模型兼容接口返回的原始 JSON。
- 将脱敏后的模型交互接入 SLF4J 或日志平台。
- 按模型关闭高敏感或高流量场景的正文日志。

## 快速开始

默认 `BaseChatConfig.logEnabled=true`。生产环境建议先关闭：

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setLogEnabled(false);
```

或者在应用启动时替换全局 Logger：

```java
ChatMessageLogger.setLogger(new DefaultChatMessageLogger(
    line -> applicationLogger.debug(mask(line))
));
```

`ChatMessageLogger` 是进程级静态门面，替换操作应在创建请求之前完成。

## 核心组件

```java
public interface IChatMessageLogger {
    void logRequest(BaseChatConfig config, String message);
    void logResponse(BaseChatConfig config, String message);
}
```

- `ChatMessageLogger` 保存一个全局实现，并拒绝设置 null。
- `DefaultChatMessageLogger` 仅在 Config 非 null 且 `isLogEnabled()` 时输出。
- provider/model 缺失时，默认文本使用源码中的 `unknow`。

## 记录时机

同步请求在责任链末端构建 Body 后记录请求，并在 Client 返回或抛出时的 `finally` 中记录响应。调用在获得响应前失败时，响应日志可能是空字符串。

流式请求会记录最终请求 Body；具体响应分片的记录行为取决于流式 Client/Listener，而不是 `BaseChatModel` 的同步响应日志块。不要假设每个流式分片都会通过 `IChatMessageLogger.logResponse(...)`。

## 自定义实现

```java
public class Slf4jChatLogger implements IChatMessageLogger {
    private final Logger logger = LoggerFactory.getLogger("LLM.Chat");

    @Override
    public void logRequest(BaseChatConfig config, String body) {
        if (config != null && config.isLogEnabled()) {
            logger.debug("LLM request provider={}, model={}, body={}",
                config.getProvider(), config.getModel(), redact(body));
        }
    }

    @Override
    public void logResponse(BaseChatConfig config, String body) {
        if (config != null && config.isLogEnabled()) {
            logger.debug("LLM response provider={}, model={}, body={}",
                config.getProvider(), config.getModel(), redact(body));
        }
    }
}

ChatMessageLogger.setLogger(new Slf4jChatLogger());
```

自定义实现自己负责线程安全、异步队列、日志级别、采样、截断和脱敏。`setLogger()` 本身没有同步或 volatile 语义，不应在请求并发期间频繁切换。

## 敏感数据治理

请求 Body 可能包含完整历史、个人信息、Tool Schema 和 Tool 结果；响应可能包含模型复述的敏感内容。Header 不在这里的 Body 日志中，但 Prompt 可能本身包含 token 或凭证。

1. 默认关闭生产正文采集，按问题和租户短时开启。
2. 用 JSON Parser 按字段脱敏，不依赖易漏字段的单个正则。
3. 限制长度和保留期，并对日志访问设置权限。
4. 不把调试日志当作不可抵赖的审计记录。
5. 自定义 Logger 内部失败不能反向影响模型主流程。

## 常见问题

### 为什么设置 `logEnabled=false` 仍看到网络日志？

该开关只控制 `IChatMessageLogger`。HTTP Client、SSE、SDK 或应用日志框架可能还有独立日志配置。

### 可以为单次 ChatOptions 关闭吗？

当前开关位于 `BaseChatConfig`，不是 `ChatOptions`。并发请求共享 Config 时不要临时切换字段；需要按请求采样可在自定义 Logger 中结合业务上下文判断。

### 默认实现会自动脱敏吗？

不会。它原样输出 Body，生产使用前必须关闭或替换。

## 下一步

- [对话拦截器](./chat-interceptor.md)
- [对话上下文](./chat-context.md)
- [错误重试](./retry.md)

</div>
