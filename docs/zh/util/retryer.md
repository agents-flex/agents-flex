# Retryer
<div v-pre>

`Retryer` 是 Agents-Flex 内置的通用重试执行器，提供可配置的重试策略、退避机制和异常过滤器。该组件适用于所有需要稳定性保障的场景，如网络请求、大模型 API 调用、数据库操作等。

Retryer 设计为**线程安全**、**可复用**，并提供构建式 API 方便开发者灵活扩展。


## 1. 核心概念

### 1.1 重试（Retry）

在执行某项任务失败时，根据预设策略自动再次执行，用于提升系统稳定性与容错能力。

### 1.2 重试条件

Retryer 支持基于两类条件触发重试：

* **异常（Exception Predicate）**：发生指定类型的异常时重试
* **返回结果（Result Predicate）**：当结果不满足条件时重试（如响应为空、code 非 200 等）

### 1.3 退避（Backoff）

为避免短时间大量重试导致雪崩，Retryer 支持：

* 固定延迟（Fixed Delay）
* 指数退避（Exponential Backoff）
* 最大延迟限制（Max Delay）
* 全局超时时间（Total Timeout）

### 1.4 可复用性与线程安全

Retryer 本身无状态，可在应用中作为单例复用（例如用于 API 调用统一策略）。



## 2. 使用场景

Retryer 常用于以下场景：

#### 调用外部 API

网络波动、超时、偶发 500 错误等可通过重试平滑掉。

#### 调用大语言模型（LLM）服务

LLM API 高并发下可能出现 timeout 或 rate limit，适合在 SDK 中统一加重试。

#### 消息队列 / RPC 调用

防止瞬时网络抖动导致消息处理失败。

#### 数据库访问

连接池偶发重置或瞬时阻塞，可以重试。

#### IO 操作（文件、S3、OSS）

对临时异常的容错。



## 3. 快速入门

Retryer 提供两种使用方式：



### 3.1 快速静态方法（推荐入门）

#### Callable 重试

```java
String result = Retryer.retry(
    () -> httpClient.get("https://api.example.com/data"),
    3,
    200
);
```

含义：

* 最多重试 3 次
* 初始延迟 200ms（固定）



#### Runnable 重试

```java
Retryer.retry(
    () -> System.out.println("Retry test"),
    3,
    100
);
```



### 3.2 完整构建器（Builder）

```java
Retryer retryer = Retryer.builder()
    .maxRetries(5)
    .initialDelayMs(200)
    .exponentialBackoff()
    .maxDelayMs(5000)
    .operationName("fetch-user")
    .build();

String result = retryer.execute(() -> fetchUser("123"));
```



## 4. 配置说明与进阶用法

以下为 Retryer 的全部可配置项及其作用。



### 4.1 最大重试次数（maxRetries）

```java
.maxRetries(3)
```

总执行次数为 `maxRetries + 1`（含首次执行）。
默认值：2



### 4.2 初始延迟（initialDelayMs）

```java
.initialDelayMs(200)
```

首次失败后的延迟，默认 100ms。



### 4.3 最大延迟（maxDelayMs）

```java
.maxDelayMs(5000)
```

在指数退避模式下用作上限。



### 4.4 启用指数退避（exponentialBackoff）

```java
.exponentialBackoff()
```

延迟增长公式：

```
delay(n) = min(initialDelay * 2^n, maxDelay)
```

用于防止雪崩，推荐网络调用开启。



### 4.5 全局超时（totalTimeoutMs）

```java
.totalTimeoutMs(8000)
```

超过总耗时会直接停止重试并抛异常。



### 4.6 基于异常的重试条件（retryOnException）

默认规则为常见网络异常：

* SocketTimeoutException
* ConnectException
* UnknownHostException
* IOException

也可自定义：

```java
.retryOnException(e -> e instanceof MyBizException)
```



### 4.7 基于结果的重试条件（retryOnResult）

```java
.retryOnResult(result -> result == null || result.equals("ERROR"))
```

常用于：

* HTTP 响应异常
* 业务 code 不为 0
* LLM 输出为空



### 4.8 操作名称（operationName）

用于日志定位：

```java
.operationName("sync-user-info")
```



## 5. 高级用法



### 5.1 结合 LLM API 使用（推荐）

```java
Retryer retryer = Retryer.builder()
    .maxRetries(4)
    .exponentialBackoff()
    .initialDelayMs(300)
    .retryOnException(e -> true) // 所有异常都重试
    .retryOnResult(r -> r == null || ((String) r).isBlank())
    .operationName("llm-completion")
    .build();

String resp = retryer.execute(() -> llmClient.chat(prompt));
```

适合 Agents-Flex 与 OpenAI/阿里/字节等模型统一重试策略。



### 5.2 重试直到业务成功

```java
.retryOnResult(r -> ((ApiResponse)r).getCode() != 0)
```



### 5.3 与分布式任务结合

将 Retryer 作为全局单例：

```java
public static final Retryer API_RETRY = Retryer.builder()
    .maxRetries(3)
    .exponentialBackoff()
    .build();
```

供整个系统共享。



### 5.4 重试 Runnable（无返回）

```java
retryer.execute(() -> syncToDB(user));
```



## 6. 核心组件解释

Retryer 内部包含以下关键组件：

### 6.1 Builder

用于构建配置，不包含执行逻辑。

### 6.2 Retryer

核心执行器：

* 控制执行循环
* 控制重试条件
* 实现退避逻辑
* 管理超时
* 捕获最终异常

### 6.3 Predicates

用于判定是否需要再次重试：

* retryOnException
* retryOnResult

### 6.4 RetryException

当超过最大重试次数，或超时触发，抛出该异常。



## 7. 源码解析（逐段说明）

以下为关键执行逻辑概念解读。



### 7.1 重试主循环

```java
for (int attempt = 0; attempt <= maxRetries; attempt++) { ... }
```

执行次数 = maxRetries + 1（含首次尝试）。



### 7.2 处理返回结果触发重试

```java
if (attempt < maxRetries && retryOnResult.test(result)) {
    sleep...
    continue;
}
```



### 7.3 处理异常触发重试

```java
catch (Exception e) {
    if (attempt < maxRetries && retryOnException.test(e)) {
        sleep...
    } else {
        break;
    }
}
```



### 7.4 指数退避实现

```java
currentDelay = Math.min(currentDelay * 2, maxDelayMs);
```



### 7.5 全局超时控制

```java
long deadline = now + totalTimeoutMs;
if (currentTime > deadline) throw TimeoutException;
```



### 7.6 安全睡眠（可中断）

```java
TimeUnit.MILLISECONDS.sleep(sleepMs);
```


</div>
