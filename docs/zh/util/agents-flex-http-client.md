# AgentsFlexHttpClient
<div v-pre>

`AgentsFlexHttpClient` 是 Agents-Flex 内部统一的 HTTP 客户端，基于 OkHttp 实现。它提供文本、字节数组、原始响应和 multipart 请求 API，并集成 Agents-Flex 的 Trace Context 传播、HTTP Span 和 Metrics。

```java
import com.agentsflex.core.model.client.AgentsFlexHttpClient;

AgentsFlexHttpClient httpClient = AgentsFlexHttpClient.getDefault();
String json = httpClient.get("https://api.example.com/models");
```

`AgentsFlexHttpClient` 位于 `agents-flex-core` 模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 1. 创建客户端

### 1.1 共享默认客户端

通常应复用全局默认实例，以共享 OkHttp 的连接池和线程池：

```java
AgentsFlexHttpClient httpClient = AgentsFlexHttpClient.getDefault();
```

### 1.2 使用自定义 OkHttpClient

需要为某个业务配置独立的超时、拦截器或证书时，可通过构造函数注入：

```java
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

OkHttpClient okHttpClient = new OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build();

AgentsFlexHttpClient httpClient = new AgentsFlexHttpClient(okHttpClient);
```

::: warning
`OkHttpClient` 应长期复用，不要为每次请求创建新实例。
:::

## 2. 常用请求

### 2.1 GET 文本和字节数组

```java
String json = httpClient.get("https://api.example.com/models");

Map<String, String> headers = new HashMap<>();
headers.put("Authorization", "Bearer " + apiKey);
headers.put("Accept", "application/json");

String user = httpClient.get("https://api.example.com/users/42", headers);
byte[] image = httpClient.getBytes("https://cdn.example.com/avatar.png");
```

`get()` 会将完整响应体读入内存并返回字符串；`getBytes()` 则返回完整字节数组。不受控的大文件应使用 `getResponse()` 流式读取。

### 2.2 POST、PUT 和 DELETE

```java
Map<String, String> headers = Collections.singletonMap(
    "Authorization", "Bearer " + apiKey
);

String created = httpClient.post(
    "https://api.example.com/jobs",
    headers,
    "{\"name\":\"extract\"}"
);

String updated = httpClient.put(
    "https://api.example.com/jobs/42",
    headers,
    "{\"enabled\":true}"
);

String deleted = httpClient.delete(
    "https://api.example.com/jobs/42",
    headers,
    null
);
```

这些方法默认使用 `application/json; charset=utf-8` 构造请求体。`payload` 为 `null` 时会发送空字符串请求体。`postBytes()` 可用于返回二进制响应的 POST 接口。

### 2.3 只读状态码

```java
int status = httpClient.getStatusCode(url, headers);
if (status == 404) {
    // 资源不存在
}
```

`getStatusCode()` 在读取状态码后会立即关闭响应，不读取响应体。

## 3. 读取原始响应

`getResponse()` 返回 OkHttp `Response`，适合检查响应头、状态码或流式下载。调用方必须关闭返回的响应：

```java
import okhttp3.Response;
import okhttp3.ResponseBody;

try (Response response = httpClient.getResponse(url, headers)) {
    if (!response.isSuccessful()) {
        throw new IllegalStateException("HTTP " + response.code());
    }

    ResponseBody body = response.body();
    if (body != null) {
        try (InputStream input = body.byteStream()) {
            Files.copy(input, targetPath);
        }
    }
}
```

::: danger
未关闭 `Response` 或其 `ResponseBody` 会占用连接资源，并使请求的可观测 Span 无法在正确的响应体消费边界结束。应始终使用 try-with-resources。
:::

## 4. Multipart 上传

`multipartString()` 和 `multipartBytes()` 接受 `Map<String, Object>` 作为表单。值类型可以是 `File`、`InputStream`、`byte[]` 或普通对象：

```java
Map<String, Object> form = new HashMap<>();
form.put("file", new File("report.pdf"));
form.put("purpose", "document-extraction");

String result = httpClient.multipartString(uploadUrl, headers, form);
```

- `File`、`InputStream` 和 `byte[]` 使用 `application/octet-stream` 上传。
- 普通对象通过 `String.valueOf(value)` 转换为文本表单项。
- 传入的 `InputStream` 不由客户端关闭，调用方负责管理其生命周期。

`multipart()` 直接返回原始 `Response`，同样必须由调用方关闭。

## 5. Gzip 响应

`AgentsFlexHttpClient` 会向调用方返回解压后的 gzip 响应体：

- 未显式设置 `Accept-Encoding` 时，OkHttp 会自动添加 `Accept-Encoding: gzip` 并透明解压。
- 调用方显式设置 `Accept-Encoding: gzip` 时，OkHttp 本身不会透明解压，`AgentsFlexHttpClient` 会对仍带有 `Content-Encoding: gzip` 的响应执行兜底解压。
- 解压后会移除已失效的 `Content-Encoding` 和 `Content-Length` 响应头。
- 当服务端返回 `application/zip` 文件时，客户端不会展开 ZIP 文件；这与 HTTP gzip 传输编码是两个不同概念。

因此，一般不需要手动使用 `GZIPInputStream` 处理响应。

## 6. 状态码与异常

`get()`、`post()` 等便捷方法会返回响应体，不会因 4xx 或 5xx 状态码自动抛出异常。如果业务需要根据 HTTP 状态分支，应使用 `getResponse()` 检查 `response.isSuccessful()` 或使用 `getStatusCode()`。

网络连接、超时和响应体读取中的 `IOException` 会被包装为 `UncheckedIOException`：

```java
try {
    String body = httpClient.get(url);
} catch (UncheckedIOException e) {
    // e.getCause() 为原始 IOException
}
```

对临时网络错误可以配合 [Retryer](/zh/util/retryer) 使用，但应避免在非幂等请求上无条件重试。

## 7. 默认配置和可观测性

默认客户端的连接超时为 60 秒、读取超时为 300 秒、写入超时为 60 秒。可通过 `okhttp.*` 系统属性或对应的 `OKHTTP_*` 环境变量调整超时、连接池和代理设置。

::: warning
默认 OkHttpClient 在首次使用时初始化，配置应在此之前完成。如果需要精确控制配置，建议构造并注入独立的 `OkHttpClient`。
:::

开启 Agents-Flex 可观测性后，客户端会：

- 创建 HTTP CLIENT Span，记录请求方法、服务器地址和响应状态码。
- 注入 `traceparent` 等 Trace Context 请求头。
- 记录请求数、失败数和包含响应体消费时间的延迟指标。
- 在开放响应读到 EOF、读取失败或显式关闭时结束 Span。

</div>
