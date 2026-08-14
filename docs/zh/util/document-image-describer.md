# DocumentImageDescriber 图片描述工具

<div v-pre>

`DocumentImageDescriber` 是 `agents-flex-core` 提供的文档增强工具。它会查找 Markdown 正文中的图片，调用支持视觉输入的 `ChatModel` 生成描述，并将描述以引用块形式插入图片后方。

处理前：

```markdown
内容内容内容
![](https://example.com/chart.png)
```

处理后：

```markdown
内容内容内容
![](https://example.com/chart.png)
> 一张展示季度增长趋势的折线图。
```

生成的描述属于 `Document.content` 的一部分，可以继续参与文档切分、Embedding 和检索。

## 1. 引入依赖

Maven：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

还需要引入一个具体的 ChatModel 模块，并选择支持图片输入的模型。以实际使用的供应商和模型能力为准。

## 2. 快速开始

```java
import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentImageDescriber;

Document document = Document.of(
    "季度经营数据如下：\n" +
    "![](https://example.com/chart.png)"
);

DocumentImageDescriber describer = new DocumentImageDescriber(chatModel);
describer.describe(document);

System.out.println(document.getContent());
```

`describe(Document)` 会直接更新传入文档的 `content`，并返回同一个 `Document` 实例。文档的 ID、标题、向量和 Metadata 不会被修改。

也可以只处理 Markdown 字符串：

```java
String enhancedMarkdown = describer.describe(sourceMarkdown);
```

## 3. 配置模型参数

默认使用温度 `0.2`。可以通过 `ChatOptions` 指定视觉模型和其他生成参数：

```java
describer.setChatOptions(ChatOptions.builder()
    .model("vision-model")
    .temperature(0.1f)
    .maxTokens(200)
    .build());
```

传入 `null` 会抛出 `IllegalArgumentException`。

## 4. 自定义描述提示词

默认提示词要求模型输出适合文档检索的简洁描述，不输出 Markdown 或额外前缀。可以替换整个提示词模板：

```java
describer.setPromptTemplate(
    "请描述图表中的指标、趋势和异常点，只输出描述正文。" +
    "图片替代文本：{alt}"
);
```

模板中的 `{alt}` 会替换为 Markdown 图片的替代文本。例如：

```markdown
![2026 年销售趋势](https://example.com/sales.png)
```

此时 `{alt}` 的值为 `2026 年销售趋势`。模板可以不包含该占位符，但不能为空。

## 5. 处理规则

| 输入情况 | 行为 |
| --- | --- |
| 普通 Markdown 图片 | 调用一次模型并在图片后插入描述 |
| 一行包含多张图片 | 按出现顺序逐张调用模型并追加描述 |
| 图片后紧跟引用块 | 视为已有描述，不再调用模型 |
| Markdown 代码围栏中的图片语法 | 作为示例代码保留，不调用模型 |
| 模型返回多行描述 | 每个非空行都转换为 `> ` 引用行 |
| 模型返回空消息 | 保留原图片，不追加描述 |
| 模型返回错误响应 | 抛出模型异常，由调用方决定重试或跳过 |

工具会保留源文本使用的 `LF`、`CRLF` 或 `CR` 换行风格。

## 6. 图片地址要求

图片地址会作为 `UserMessage.imageUrls` 发送给 ChatModel，工具本身不负责上传或下载图片。地址必须是目标模型适配器能够读取的形式，例如：

- 模型服务能够访问的 HTTP 或 HTTPS URL；
- Data URI，例如 `data:image/png;base64,...`；
- 具体 ChatModel 实现明确支持的其他地址格式。

相对地址（如 `images/chart.png`）通常无法被远程模型直接读取，应先转换为公开 URL 或 Data URI。部分 ChatModel 在配置了“仅支持 Base64 图片”能力时，会自动把 HTTP 图片转换为 Data URI。

## 7. 与文档提取组合

`DocumentExtractors` 可以先从 PDF、Word、PowerPoint 等文件中提取 Markdown 和图片，再由 `DocumentImageDescriber` 补充图片语义：

```java
String markdown = DocumentExtractors.extract(file);
Document document = Document.of(markdown);

DocumentImageDescriber describer = new DocumentImageDescriber(chatModel);
describer.describe(document);

List<Document> chunks = splitter.split(document);
```

推荐顺序是：

1. 提取文档和图片；
2. 生成图片描述；
3. 执行文档切分；
4. 生成 Embedding 并写入 Store。

默认的文档图片处理器会生成 Data URI。如果改为上传对象存储，应确保返回的 URL 对视觉模型可访问。

## 8. 成本与并发

每张待处理图片都会发起一次同步模型调用。包含大量图片的文档会相应增加耗时、Token 消耗和供应商请求次数。批量导入时应在业务层设置并发上限、超时和重试策略，并结合模型供应商的 QPS 限制控制任务速率。

重复执行时，工具会跳过后方已经存在引用块的图片。不过，如果业务会修改描述与图片之间的结构，建议额外记录处理状态，避免重复产生模型调用。

</div>
