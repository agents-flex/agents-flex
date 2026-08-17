# OCR 文档识别

Agents-Flex OCR 为图片、PDF 等文档解析场景提供统一接口。目前支持百度智能云 PaddleOCR-VL、Gitee AI 模力方舟和 MinerU。公共模型位于 `agents-flex-core`，每个供应商使用独立 Maven 模块。

## 适合解决什么问题

OCR 模块适合把扫描件、合同、报告、表格等非结构化文件转换为文本、Markdown 或结构化结果资源。公共接口屏蔽了供应商在提交协议、任务状态和结果字段上的差异，业务代码可以用相同方式提交和查询任务。

框架负责统一调用模型，但不负责文件长期存储、结果 URL 转存、业务审核和供应商费用控制。这些能力应由应用层补充。

## 模块结构

```text
agents-flex-core
└── com.agentsflex.core.model.ocr
    ├── OcrModel
    ├── OcrRequest
    ├── OcrResponse
    ├── OcrResource
    └── OcrTaskStatus

agents-flex-ocr
├── agents-flex-ocr-baidu
├── agents-flex-ocr-gitee
└── agents-flex-ocr-mineru
```

## 统一调用模型

所有供应商都实现 `OcrModel`，包含三个主要入口：

```java
// 只提交任务，立即返回任务编号和当前状态
OcrResponse submitted = model.recognize(request);

// 使用供应商任务编号查询一次，不循环、不休眠
OcrResponse latest = model.getResult(submitted.getTaskId());

// 提交并在当前线程轮询至终态或超时
OcrResponse result = model.recognizeAndWait(request, 10 * 60_000L, 3_000L);
```

`recognizeAndWait()` 适合命令行工具、测试和少量短任务。Web 服务或大量长任务应保存任务编号，通过 [异步任务模块](../async-task/overview) 在后台查询，避免长期占用请求线程。

## 如何选择调用方式

| 场景 | 推荐方式 | 原因 |
| --- | --- | --- |
| 本地工具、集成测试 | `recognizeAndWait()` | 代码简单，可以接受阻塞线程 |
| 少量任务，应用自行调度 | `recognize()` + `getResult()` | 可以自己控制查询时间 |
| Web 服务、长任务、需要跨重启恢复 | Async Task | 持久化状态、自动查询、重试和多 Worker 协调 |

`recognizeAndWait()` 超时只代表本地停止等待。只要响应仍有 `taskId`，就可以继续查询同一个远端任务。

## 输入类型

`OcrRequest` 支持远程 URL 和本地文件，一次请求只能选择一种：

```java
OcrRequest urlRequest = OcrRequest.ofUrl("https://example.com/report.pdf");
OcrRequest fileRequest = OcrRequest.ofFile(new File("report.pdf"));
```

还可以覆盖模型或透传供应商参数：

```java
OcrRequest request = OcrRequest.ofFile(new File("report.pdf"));
request.setModel("provider-model-name");
request.putOption("language", "ch");
```

供应商对文件格式、大小、页数和远程 URL 的限制不同。框架只统一对象模型，不会绕过供应商限制。

## 统一响应

| 字段 | 说明 |
| --- | --- |
| `taskId` | 供应商任务编号，可用于继续查询 |
| `status` | 统一任务状态 |
| `text` | 纯文本识别结果，供应商提供时可用 |
| `markdown` | Markdown 识别结果，供应商提供时可用 |
| `resources` | Markdown、JSON、ZIP 等结果资源 |
| `errorCode` / `errorMessage` | 标准化错误信息 |
| metadata（元数据） | 尚未标准化的供应商原始字段 |

常见状态：

| 状态 | 含义 |
| --- | --- |
| `SUBMITTED` | 已接受，等待执行或等待首次查询 |
| `RUNNING` | 供应商正在处理 |
| `SUCCEEDED` | 已成功，可读取文本或资源 |
| `FAILED` | 供应商明确失败 |
| `CANCELED` | 供应商任务已取消 |
| `TIMED_OUT` | 本地等待超时，云端任务不一定失败 |
| `UNKNOWN` | 无法映射的供应商状态 |

当状态为 `TIMED_OUT` 时，响应会保留 `taskId`，可以继续调用 `getResult()`。

## 供应商对比

| 供应商 | Maven 模块 | 本地文件 | 远程 URL | 结果特点 |
| --- | --- | --- | --- | --- |
| 百度智能云 | `agents-flex-ocr-baidu` | Base64 上传 | 支持 | Markdown、解析 JSON 资源 |
| Gitee AI | `agents-flex-ocr-gitee` | multipart 上传 | 支持，适配器先下载再上传 | 可直接返回 Markdown，结果 URL 短期有效 |
| MinerU | `agents-flex-ocr-mineru` | 预签名 URL 上传 | 支持 | 常返回完整结果 ZIP |

选择供应商时，除了模型效果，还应评估文件大小和页数限制、输入方式、结果链接有效期、并发额度及数据合规要求。

## 结果处理建议

1. 先检查 `status` 和 `isError()`，不要只判断 `taskId`。
2. 成功后同时检查 `text`、`markdown` 和 `resources`，不同供应商输出位置不同。
3. 尽快把临时结果 URL 转存到自己的对象存储。
4. 保存框架任务 ID、供应商任务 ID 和必要的元数据，便于审计和故障恢复。
5. 对关键文档增加内容完整性校验或人工审核，不要把 OCR 结果默认视为绝对准确。

内置模型的 `getResult()` 会统一解析不同的结果位置，并把最终内容写入响应；等待入口通过轮询该方法获得
相同结果：

```java
OcrResponse response = model.recognizeAndWait(request);
String markdown = response.getMarkdown();
```

`getResult()` 支持内联 Markdown、Markdown 下载资源、ZIP 中的 Markdown，并以纯文本作为最后降级结果。
通过
`BaseOcrModel.setDocumentImagePublisher()` 可以把结果图片上传到对象存储，并将 Markdown 图片引用替换为
Publisher 返回的 URL。图片可以来自 ZIP、Base64 Data URI 或 HTTP/HTTPS 地址；配置 Publisher 后，远程图片
也会被下载并转存。未配置 Publisher 时保留供应商返回的远程 URL。

## 下一步

- [快速开始](./getting-started)
- [百度智能云](./baidu)
- [Gitee AI](./gitee)
- [MinerU](./mineru)
- [使用异步任务持久化跟踪](../async-task/getting-started)
