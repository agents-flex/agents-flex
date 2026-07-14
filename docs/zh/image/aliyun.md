<div v-pre>

# 阿里云图片生成

Agents-Flex 提供两个阿里云图片适配器：`agents-flex-image-bailian` 通过百炼 HTTP API 调用通义万相，`agents-flex-image-qwen` 通过 DashScope Java SDK 调用图片生成模型。新项目建议优先使用 Bailian 适配器。

## Bailian 适配器

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-bailian</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.bailian.BailianImageModel;
import com.agentsflex.image.bailian.BailianImageModelConfig;

BailianImageModelConfig config = new BailianImageModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setModel("wan2.7-image");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一只小老虎站在高速列车里，写实摄影");
request.setSizeString("1k");
request.setN(1);

ImageResponse response = new BailianImageModel(config).generate(request);
```

| Config 字段 | 默认值 |
| --- | --- |
| `endpoint` | `https://dashscope.aliyuncs.com/` |
| `requestPath` | `/api/v1/services/aigc/multimodal-generation/generation` |
| `model` | `wan2.7-image` |

Bailian 适配器将 `size` 和 `n` 映射到百炼 `parameters`。

## Qwen SDK 适配器

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-qwen</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
QwenImageModelConfig config = new QwenImageModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setModel("flux-schnell");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("雨中的竹林小路");
request.setSize(1024, 1024);
request.addOption("seed", 42);

ImageResponse response = new QwenImageModel(config).generate(request);
```

Qwen 适配器优先使用 `request.model`，否则使用 Config 模型。`seed` 通过 `options` 传入，未设置时默认为 `1`。

## 能力边界

两个适配器当前都以 `generate()` 文生图为主。`edit()`、`vary()` 和图生图接口尚未实现。

</div>
