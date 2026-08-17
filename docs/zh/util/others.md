# 其他工具类
<div v-pre>

Agents-Flex Core 在 `com.agentsflex.core.util` 包中提供了一组轻量工具，用于处理集合、反射、I/O、JSON、类型转换、消息元数据和线程池。它们随 `agents-flex-core` 一起提供，不需要额外引入工具库。

本文只描述当前源码中真实存在的公开 API。旧版文档列出的 `VectorUtil` 在当前代码库中不存在，因此不再列入。

## 1. 工具选择

| 工具 | 主要用途 | 典型场景 |
| --- | --- | --- |
| `ArrayUtil` | 对象数组判空、拼接和包含判断 | 合并 Tool、拦截器等数组配置 |
| `CollectionUtil` | 集合判空，读取列表首尾元素 | 安全处理模型返回列表 |
| `StringUtil` | 文本判空、整数和 JSON 对象外形判断 | 参数校验与响应预检查 |
| `ClassUtil` | 代理类还原、原始类型包装、字段和方法扫描 | 注解扫描、反射工具构建 |
| `MapUtil` | 兼容 Java 8 的 `computeIfAbsent` | 框架缓存初始化 |
| `HashUtil` | MD5、SHA-256、HMAC-SHA256、十六进制编码 | 签名、缓存键和内容摘要 |
| `IOUtil` | 字节读写、流复制、UTF-8 读取 | 文件和网络响应处理 |
| `ImageUtil` | 图片转 Data URI、扩展名转 MIME 类型 | 只接受 Base64 图片的模型 |
| `JSONUtil` | 基于 fastjson2 JSONPath 读取字段 | 解析不同模型服务的响应 |
| `JsonSanitizer` | 修复模型生成的类 JSON 工具参数 | Tool Calling 参数容错 |
| `TypeConverter` | 基础类型、时间、枚举和泛型集合转换 | 将模型参数转换为 Java 参数 |
| `JsonSchemaTypeMapper` | Java 类型映射为 JSON Schema 类型 | 自动生成 Tool 参数定义 |
| `MessageUtil` | 查找最后一条用户消息 | 对话拦截器和上下文处理 |
| `Metadata` | 线程安全的键值元数据容器 | 保存 traceId、租户和模型标签 |
| `NamedThreadFactory` | 创建具有稳定名称的线程 | 日志定位和后台任务 |
| `NamedThreadPools` | 创建命名的固定、缓存和调度线程池 | 异步模型调用与定时任务 |
| `Copyable<T>` | 统一对象复制约定 | 消息和 ToolCall 的隔离副本 |

## 2. 数组、集合与字符串

### 2.1 ArrayUtil

`ArrayUtil` 仅处理对象数组，不提供 `int[]` 等基本类型数组的重载。

```java
import com.agentsflex.core.util.ArrayUtil;

String[] first = {"chat", "rag"};
String[] second = {"agent"};

boolean empty = ArrayUtil.isEmpty(first);              // false
String[] merged = ArrayUtil.concat(first, second);     // chat, rag, agent
String[] appended = ArrayUtil.append(first, "skills");
boolean contains = ArrayUtil.contains(merged, "rag"); // true
```

主要方法：

| 方法 | 行为 |
| --- | --- |
| `isEmpty(array)` / `isNotEmpty(array)` | `null` 和零长度数组均视为空 |
| `concat(first, second)` | 合并两个数组；两者都为 `null` 时抛出 `IllegalArgumentException` |
| `concat(first, second, third, others...)` | 按顺序合并多个数组 |
| `append(first, values...)` | 在数组末尾追加元素 |
| `contains(array, value)` | 使用 `Objects.equals` 比较，支持查找 `null` |

::: tip
当其中一个数组为空时，`concat` 和 `append` 可能直接返回另一个原数组，而不是创建副本。需要修改结果且不希望影响原数组时，应自行复制。
:::

### 2.2 CollectionUtil

```java
List<String> models = Arrays.asList("qwen", "deepseek");

boolean available = CollectionUtil.hasItems(models); // true
String first = CollectionUtil.firstItem(models);     // qwen
String last = CollectionUtil.lastItem(models);       // deepseek
```

- `noItems(collection)`：集合为 `null` 或空集合时返回 `true`。
- `hasItems(collection)`：与 `noItems` 相反。
- `firstItem(list)` / `lastItem(list)`：列表为空时返回 `null`，不会抛出越界异常。

### 2.3 StringUtil

```java
String endpoint = StringUtil.firstHasText(
    System.getenv("MODEL_ENDPOINT"),
    "https://api.example.com"
);

boolean validName = StringUtil.hasText("qwen");
boolean integer = StringUtil.isNumeric("-128");
boolean objectLike = StringUtil.isJsonObject("{\"name\":\"demo\"}");
```

| 方法 | 说明 |
| --- | --- |
| `hasText` / `noText` | 区分有内容文本与 `null`、空串、纯空白文本 |
| `allHasText` / `anyHasText` | 检查多个字符串是否全部或至少一个有内容 |
| `allNoText` / `anyNoText` | 检查多个字符串是否全部或至少一个无内容 |
| `firstHasText` | 返回第一个有内容的字符串，没有时返回 `null` |
| `isNumeric` | 只接受带可选正负号的十进制整数，不接受小数、科学计数法和十六进制 |
| `isJsonObject` / `notJsonObject` | 只检查 `{...}` 和冒号等外形特征，不执行完整 JSON 解析 |

::: warning
`isJsonObject` 是快速预检查，不保证字符串一定是合法 JSON。需要严格校验时应交给 JSON 解析器。
:::

## 3. 反射与 Map

### 3.1 ClassUtil

`ClassUtil` 主要服务于框架的反射扫描，能够识别 JDK Proxy 以及部分 CGLIB、Javassist 代理类。

```java
Class<?> usefulClass = ClassUtil.getUsefulClass(service.getClass());

List<Field> fields = ClassUtil.getAllFields(
    usefulClass,
    field -> !Modifier.isStatic(field.getModifiers())
);

Method handler = ClassUtil.getFirstMethod(
    usefulClass,
    method -> method.getName().equals("handle")
);
```

常用能力包括：

- `isProxy(clazz)`：判断是否为已支持的框架代理或 JDK 动态代理。
- `getUsefulClass(clazz)`：取得适合扫描的原始类；JDK 动态代理有多个接口时只返回第一个。
- `getUsefulClasses(clazz)`：返回所有适合扫描的类或接口。
- `getWrapType(clazz)`：把 `int.class` 等原始类型转换为对应包装类型。
- `isArray(clazz)`：判断数组类型。
- `getAllFields` / `getAllMethods`：从当前类向父类递归收集成员，不包含 `Object`。
- `getFirstField` / `getFirstMethod`：按 Predicate 返回第一个匹配成员，没有时返回 `null`。

这些方法返回的反射对象不会自动调用 `setAccessible(true)`，访问私有成员时需要调用方自行处理访问权限。

### 3.2 MapUtil

`MapUtil` 当前只有一个公开方法：

```java
Map<String, List<String>> cache = new ConcurrentHashMap<>();
List<String> values = MapUtil.computeIfAbsent(
    cache,
    "models",
    key -> new ArrayList<>()
);
```

它用于规避 Java 8 的 `ConcurrentHashMap.computeIfAbsent` 性能问题。在较新 JDK 上会直接调用 Map 自身的 `computeIfAbsent`。普通业务代码如果不需要兼容该问题，也可以使用 JDK API。

## 4. 摘要、I/O 与图片

### 4.1 HashUtil

```java
String contentHash = HashUtil.sha256("Agents-Flex");
String signature = HashUtil.hmacSHA256ToBase64(payload, secret);
String hex = HashUtil.bytesToHex(bytes);
```

- `md5(text)`：生成小写十六进制 MD5。
- `sha256(text)`：生成小写十六进制 SHA-256。
- `hash(algorithm, text)`：使用 JCA 支持的算法生成十六进制摘要。
- `hmacSHA256ToBase64(content, secret)`：生成 Base64 编码的 HMAC-SHA256。
- `bytesToHex(bytes)`：把字节数组转成小写十六进制文本。

::: warning
MD5 不适合密码存储、身份认证或抗碰撞安全场景。密码应使用专用密码哈希算法；接口签名优先使用 HMAC-SHA256。
:::

### 4.2 IOUtil

```java
byte[] bytes = IOUtil.readBytes(new File("prompt.txt"));
IOUtil.writeBytes(bytes, new File("prompt-copy.txt"));

try (InputStream input = Files.newInputStream(path)) {
    String text = IOUtil.readUtf8(input);
}
```

| 方法 | 说明 |
| --- | --- |
| `writeBytes(bytes, file)` | 写入文件并自动关闭内部创建的输出流，I/O 错误包装为 `UncheckedIOException` |
| `readBytes(file)` | 读取完整文件并自动关闭内部创建的输入流 |
| `readBytes(inputStream)` | 读取完整输入流，错误包装为 `UncheckedIOException` |
| `copy(input, output)` | 使用 8 KiB 缓冲区复制到 `OutputStream` |
| `copy(input, bufferedSink)` | 复制到 Okio `BufferedSink` |
| `readUtf8(input)` | 读取完整输入流并按 UTF-8 解码 |

传入的 `InputStream`、`OutputStream` 或 `BufferedSink` 不会由这些方法关闭，其生命周期由调用方管理。`readBytes` 和 `readUtf8` 会把全部内容放入内存，不适合直接读取不受控的大文件。

### 4.3 ImageUtil

```java
String fileDataUri = ImageUtil.imageFileToDataUri(new File("avatar.webp"));
String urlDataUri = ImageUtil.imageUrlToDataUri("https://example.com/a.png?token=xxx");
String rawDataUri = ImageUtil.imageBytesToDataUri(bytes, "image/png");
```

- `imageUrlToDataUri(url)`：通过 Agents-Flex HTTP 客户端下载图片，再根据 URL 扩展名生成 Data URI。
- `imageFileToDataUri(file)`：读取本地文件并生成 Data URI。
- `imageBytesToDataUri(bytes, mimeType)`：将已有字节编码为 Base64 Data URI。
- `getMimeTypeFromExtension(ext)`：识别 jpg、jpeg、png、gif、bmp、tif、tiff。

URL 中的查询参数和 fragment 不影响扩展名识别。内部 MIME 映射还支持 SVG、WebP、AVIF、JPEG XL 和 ICO；无法识别时默认使用 `image/jpeg`。因此无扩展名 URL 或扩展名不可信时，建议调用方从响应头确认 MIME 类型后使用 `imageBytesToDataUri`。

## 5. JSON 与类型转换

### 5.1 JSONUtil

`JSONUtil` 基于 fastjson2，并缓存字符串形式的 `JSONPath` 对象。

```java
JSONObject response = JSON.parseObject(
    "{\"data\":{\"id\":\"42\",\"score\":0.98}}"
);

Long id = JSONUtil.readLong(response, "$.data.id");
String score = JSONUtil.readString(response, JSONPath.of("$.data.score"));
```

可读取 `String`、`Integer`、`Long`、`float[]`、`JSONObject` 和 `JSONArray`。需要注意：

- 字符串路径版 `readString` 只接受实际为 String 的值；`JSONPath` 参数版会调用 `toString()`。
- `readInteger` 和 `readLong` 接受 Number 或数字字符串；非法数字字符串会抛出 `NumberFormatException`。
- `getJSONObject` 和 `getJSONArray` 对类型执行严格检查，类型不匹配时抛出 `IllegalArgumentException`。
- `readFloatArray` 会把数组中的 `null` 转为 `0.0f`；其他无法转换的元素会使整个方法返回 `null`。
- `detectErrorMessage` 读取 `error.message` 和可选的 `error.code`，适合兼容 OpenAI 风格错误响应。

### 5.2 JsonSanitizer

模型生成的 Tool 参数有时混入单引号、未加引号的字符串、JavaScript 函数、正则表达式或 `new Date()`，这些内容不是标准 JSON。`JsonSanitizer` 会保留表达式源码，但把它转换成普通 JSON 字符串，不会执行 JavaScript。

完整 API、容错边界和与 `ToolCall` 的集成方式见 [JsonSanitizer 开发者文档](/zh/util/json-sanitizer)。

```java
String raw = "参数如下：{name: 'demo', handler: function () { return 1; }}";

for (String candidate : JsonSanitizer.extractObjects(raw)) {
    String json = JsonSanitizer.sanitize(candidate);
    JSONObject args = JSON.parseObject(json);
    // name -> demo
    // handler -> function () { return 1; }
}
```

| 方法 | 用途 |
| --- | --- |
| `sanitize(text)` | 将常见 JavaScript 值、单引号字符串和未加引号的值转换为合法 JSON 字符串值 |
| `extractObjects(text)` | 从带说明文字的模型输出中提取所有完整 `{...}` 对象候选 |
| `completeObject(text)` | 为缺少外层或结束花括号的对象候选补全结构 |

该工具是模型输出的容错层，不是通用 JavaScript 解析器。业务仍应在 JSON 解析后校验字段类型、必填项和权限。

### 5.3 TypeConverter

`TypeConverter` 是 Tool 参数绑定使用的通用转换器。简单类型直接转换，实体对象和泛型集合使用 fastjson2 兜底。

```java
Integer count = TypeConverter.convert("12", Integer.class);
Boolean enabled = TypeConverter.convert("yes", Boolean.class);
LocalDate date = TypeConverter.convert("2026-08-09", LocalDate.class);

Integer fallback = TypeConverter.convert("invalid", Integer.class, 0);

Type listType = TypeConverter.createParameterizedType(List.class, Long.class);
List<Long> ids = TypeConverter.convert("[1, 2, 3]", listType);
```

支持的主要目标包括：

- 原始类型及包装类型、`BigDecimal`、`BigInteger`。
- `Boolean`：识别 true/false、1/0、yes/no、y/n、on/off，不区分大小写。
- `Character`：字符串取首字符，数字按字符码转换。
- `Date`、`LocalDate`、`LocalDateTime`、`Instant`：支持时间戳、ISO 和多种常见日期格式。
- Enum：字符串按枚举名称匹配，数字按枚举声明顺序索引。
- 实体类、`List<T>` 等复杂类型：通过 JSON 序列化和反序列化转换。

不带默认值的 `convert` 在简单值非法时通常抛出 `IllegalArgumentException`，复杂 JSON 转换失败时抛出 `TypeConverter.ConversionException`。带默认值的重载会捕获转换异常，并在失败或结果为 `null` 时返回默认值。

### 5.4 JsonSchemaTypeMapper

该工具把 Java 类型转换为 Tool 参数使用的 JSON Schema 基础类型：

```java
String integerType = JsonSchemaTypeMapper.mapToSchemaType(Long.class); // integer
String arrayType = JsonSchemaTypeMapper.mapToSchemaType(List.class);   // array
String objectType = JsonSchemaTypeMapper.mapToSchemaType(Map.class);   // object

JsonSchemaTypeMapper.registerStrategy(Money.class, type -> "number");
```

整数、浮点数、布尔、字符串、数组/集合和 Map 分别映射为 `integer`、`number`、`boolean`、`string`、`array` 和 `object`。时间、UUID、URI、URL 和枚举按字符串处理，自定义实体默认映射为 `object`。

`resolveArrayItemType(type)` 可从 `List<String>`、泛型数组和通配符中推导元素类型。无法确定的类型变量回退为 `object`；传入 `null` 时回退为 `string`。`registerStrategy` 注册的是进程级全局策略，修改后会清除该类型缓存；测试或热更新场景可以调用 `clearCache()` 清空普通映射缓存。

## 6. 消息与元数据

### 6.1 MessageUtil

`MessageUtil` 当前只负责从消息列表末尾向前查找最后一条 `UserMessage`：

```java
List<Message> messages = Arrays.asList(
    new SystemMessage("你是助手"),
    new UserMessage("第一次提问"),
    new AiMessage("第一次回答"),
    new UserMessage("继续说明")
);

UserMessage latest = MessageUtil.findLastUserMessage(messages);
```

列表为 `null`、空列表或没有用户消息时返回 `null`。该方法不修改消息列表。

### 6.2 Metadata

`Metadata` 是可序列化、延迟初始化的线程安全元数据容器。Message、ModelOptions 等框架对象会复用这套能力。

```java
Metadata metadata = new Metadata();
metadata.putMetadata("traceId", "trace-1001");
metadata.putMetadata("retry", 2);

String traceId = metadata.getMetadata("traceId", String.class, "unknown");
int retry = metadata.getMetadata("retry", Integer.class, 0);

metadata.putMetadataIfAbsent("tenant", "default");
boolean contains = metadata.containsMetadata("tenant");
metadata.removeMetadata("tenant");
```

`getMetadata(key, type, defaultValue)` 会在键不存在或类型不匹配时返回默认值，不执行类型转换。`putMetadata(Map)` 是合并操作，而 `setMetadataMap(Map)` 会用防御性副本替换全部元数据。`addMetadata` 是兼容旧版本的废弃别名，新代码应使用 `putMetadata`。

::: warning
底层使用 `ConcurrentHashMap`，因此 key 和 value 都不能为 `null`。`getMetadataMap()` 为兼容性直接暴露内部 Map，并且容器未初始化时可能返回 `null`；业务代码应优先使用上述元数据方法。
:::

### 6.3 Copyable

`Copyable<T>` 只定义一个 `T copy()` 方法，用于约定对象能够返回内容相同但状态独立的副本。Agents-Flex 的消息和 `ToolCall` 实现了该接口。

```java
UserMessage original = new UserMessage("分析图片");
original.addImageUrl("https://example.com/a.png");

UserMessage copied = original.copy();
copied.addImageUrl("https://example.com/b.png");
```

具体是深拷贝还是浅拷贝由实现类决定。例如 `UserMessage.copy()` 会复制媒体 URL 列表和消息状态。

## 7. 命名线程与线程池

### 7.1 NamedThreadFactory

```java
ThreadFactory factory = new NamedThreadFactory("model-worker", true);
Thread thread = factory.newThread(() -> runTask());

// 线程名：model-worker-thread-1
// daemon：true
thread.start();
```

构造器可指定名称前缀和是否为守护线程。默认不是守护线程；无参构造器使用 `pool-N-thread-M` 格式。线程会加入当前安全管理器或当前线程对应的 `ThreadGroup`。

### 7.2 NamedThreadPools

```java
ExecutorService workers = NamedThreadPools.newFixedThreadPool(4, "rag-worker");
ScheduledExecutorService scheduler =
    NamedThreadPools.newScheduledThreadPool(1, "model-health");

try {
    workers.submit(() -> indexDocuments());
    scheduler.scheduleAtFixedRate(this::checkModels, 0, 30, TimeUnit.SECONDS);
} finally {
    workers.shutdown();
    scheduler.shutdown();
}
```

| 工厂方法 | 实现特征 |
| --- | --- |
| `newFixedThreadPool(prefix)` | 线程数取 `availableProcessors()` |
| `newFixedThreadPool(n, name)` | 固定 n 个线程，队列容量为 `n * 2` |
| `newCachedThreadPool(name/factory)` | 0 个核心线程、60 秒空闲回收、`SynchronousQueue` |
| `newScheduledThreadPool(size, name/factory)` | 创建 `ScheduledThreadPoolExecutor` |

固定线程池使用有界队列，队列满且所有线程繁忙时采用 `ThreadPoolExecutor` 默认拒绝策略并抛出 `RejectedExecutionException`。缓存线程池最大线程数为 `Integer.MAX_VALUE`，不应接收无限制的外部任务。所有工厂方法都不会自动关闭线程池，应用必须在生命周期结束时调用 `shutdown()` 或 `shutdownNow()`。

</div>
