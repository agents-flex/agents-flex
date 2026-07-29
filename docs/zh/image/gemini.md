<div v-pre>

# Gemini Nano Banana 图片生成

`agents-flex-image-gemini` 通过 Gemini `generateContent` REST API 支持 Nano Banana 文生图、图片编辑和多图融合。默认使用 Google 推荐的 `gemini-3.1-flash-image`（Nano Banana 2）。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-gemini</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 文生图

```java
GeminiImageModelConfig config = new GeminiImageModelConfig();
config.setApiKey(System.getenv("GEMINI_API_KEY"));

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一张现代植物学海报，留白充足，中文标题清晰");
request.setResolution("2K");
request.addOption(GeminiImageModel.OPTION_ASPECT_RATIO, "16:9");

ImageResponse response = new GeminiImageModel(config).generate(request);
response.getImage().writeToFile(new File("output/poster.png"));
```

## 图片编辑与多图融合

```java
request.setPrompt("把第一张图片中的服装自然地应用到第二张图片的人物上");
request.addInputImage(Image.ofBytes(productBytes, "image/png"));
request.addInputImage(Image.ofBytes(personBytes, "image/jpeg"));

ImageResponse response = new GeminiImageModel(config).generate(request);
```

URL 输入图片会在请求前下载并转换为 Gemini `inlineData`。在可控的生产环境中，建议直接提供字节或 Base64，以避免远程 URL 的可用性影响生成请求。

## 模型与能力

| 模型 | 产品名称 | 分辨率 | 最大输入图片数 |
| --- | --- | --- | --- |
| `gemini-3.1-flash-image` | Nano Banana 2 | `512`、`1K`、`2K`、`4K` | 14 |
| `gemini-3.1-flash-lite-image` | Nano Banana 2 Lite | `1K` | 14 |
| `gemini-3-pro-image` | Nano Banana Pro | `1K`、`2K`、`4K` | 14 |
| `gemini-2.5-flash-image` | Nano Banana | 固定 `1K` | 3 |

调用 `config.setModel(...)` 后，`supportedResolutions`、`supportedAspectRatios` 和 `maxInputImages` 会同步更新。

## 扩展配置

`options` 中除模块专用字段外的值会透传到 generateContent 顶层，因此可以传入 `tools` 等 Gemini 配置。完整 `generationConfig` 可通过 `OPTION_GENERATION_CONFIG` 合并：

```java
request.addOption("tools", Collections.singletonList(
    Collections.singletonMap("google_search", Collections.emptyMap())
));
request.addOption(GeminiImageModel.OPTION_GENERATION_CONFIG,
    Collections.singletonMap("temperature", 0.7));
```

Gemini 不支持精确指定输出图片数量，生成结果也可能同时包含文本和多张图片。本模块默认设置 `responseModalities` 为 `IMAGE`，并解析所有候选中的图片部分。

参数和模型限制以 [Google Gemini Nano Banana 图片生成文档](https://ai.google.dev/gemini-api/docs/generate-content/image-generation?hl=zh-cn) 为准。

</div>
