<div v-pre>

# OpenAI 图片生成

`agents-flex-image-openai` 调用 OpenAI Images API 的 `/v1/images/generations` 同步生成图片，支持 GPT Image 与 DALL-E 模型返回的 Base64 或 URL 图片。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-openai</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 基本用法

```java
OpenAIImageModelConfig config = new OpenAIImageModelConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("A quiet reading room with warm afternoon light");
request.setSizeString("1536x1024");
request.setQuality("high");
request.setOutputFormat("png");

ImageResponse response = new OpenAIImageModel(config).generate(request);
response.getImage().writeToFile(new File("output/reading-room.png"));
```

默认模型为 `gpt-image-1.5`。也可以通过 `config.setModel(...)` 或 `request.setModel(...)` 使用 `gpt-image-1`、`gpt-image-1-mini`、`dall-e-3` 和 `dall-e-2`。在 Config 上切换模型时，`supportedResolutions`、`supportedAspectRatios` 和 `supportedQualities` 会同步更新，便于产品刷新选择项。

## 参数映射

| Agents-Flex | OpenAI Images API |
| --- | --- |
| `model` | `model` |
| `prompt` | `prompt` |
| `n` | `n` |
| `resolution` 或 `sizeString` | `size` |
| `quality` | `quality` |
| `style` | `style`，仅 DALL-E 3 |
| `responseFormat` | `response_format`，仅 DALL-E |
| `outputFormat` | `output_format`，仅 GPT Image |
| `user` | `user` |

OpenAI 专属参数通过 `options` 设置：

```java
request.addOption(OpenAIImageModel.OPTION_BACKGROUND, "transparent");
request.addOption(OpenAIImageModel.OPTION_MODERATION, "auto");
request.addOption(OpenAIImageModel.OPTION_OUTPUT_COMPRESSION, 80);
```

当前 `ImageModel.generate()` 是同步接口，因此模块不接受 `stream=true` 或 `partial_images`。本模块聚焦图片生成端点，携带 `inputImages` 的编辑请求会在发送 HTTP 请求前返回错误。

参数和模型限制以 [OpenAI 图片生成指南](https://developers.openai.com/api/docs/guides/image-generation) 为准。

</div>
