<div v-pre>

# 火山引擎图片生成

`agents-flex-image-volcengine` 调用火山方舟 `/api/v3/images/generations`，使用 Bearer API Key 鉴权，支持 Seedream 文生图、参考图生成和组图生成。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-volcengine</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```bash
export ARK_API_KEY="your-api-key"
```

```java
VolcengineImageModelConfig config = new VolcengineImageModelConfig();
config.setApiKey(System.getenv("ARK_API_KEY"));
config.setModel(VolcengineImageModels.SEEDREAM_5_0_LITE);

VolcengineImageModel imageModel = new VolcengineImageModel(config);
```

旧版 `setAccessKey()` 为源码兼容保留并映射到 `apiKey`；`setSecretKey()` 不再参与请求。

## 文生图

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("清晨薄雾中的未来城市，电影感光线");
request.setResolution("2K");
request.setResponseFormat("url");
request.setOutputFormat("png");
request.setWatermark(false);

ImageResponse response = imageModel.generate(request);
```

## 多参考图与组图

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("保留产品外观，生成统一风格的系列广告图");
request.addInputImage(Image.ofUrl("https://example.com/front.png"));
request.addInputImage(Image.ofUrl("https://example.com/side.png"));
request.setSequentialGeneration(true);
request.setMaxImages(4);

ImageResponse response = imageModel.generate(request);
```

输入图既可使用公网 URL，也可使用 `Image.ofBytes()`；字节图片会转换成 Data URI。

## 参数映射

| Agents-Flex | 火山方舟 |
| --- | --- |
| `model` | `model` |
| `prompt` | `prompt` |
| `inputImages` | `image`，单图为字符串，多图为数组 |
| `resolution` 或 `sizeString` | `size` |
| `responseFormat` | `response_format` |
| `outputFormat` | `output_format` |
| `watermark` | `watermark` |
| `seed` | `seed` |
| `promptExtend` | `optimize_prompt` |
| `sequentialGeneration` | `sequential_image_generation` |
| `maxImages` | `sequential_image_generation_options.max_images` |

其余火山专属字段可通过 `request.addOption()` 添加到顶层请求。该接口同步返回结果，成功状态为 `SUCCEEDED`。

</div>
