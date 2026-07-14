<div v-pre>

# Stability AI 图片生成

`agents-flex-image-stability` 通过 Stability AI Stable Image API 生成图片。当前适配器调用 SD3 生成端点，并直接返回 JPEG 字节。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-stability</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 生成并保存

```java
StabilityImageModelConfig config = new StabilityImageModelConfig();
config.setApiKey(System.getenv("STABILITY_API_KEY"));

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("A cute tiger standing inside a high-speed train");

ImageResponse response = new StabilityImageModel(config).generate(request);
if (!response.isError() && !response.getImages().isEmpty()) {
    response.getImages().get(0).writeToFile(
        new File("output/stability-image.jpg")
    );
}
```

默认服务地址为 `https://api.stability.ai/`，请求路径为 `/v2beta/stable-image/generate/sd3`。适配器使用 `multipart/form-data`，并设置 `output_format=jpeg`。

## 能力边界

当前只映射 `prompt`。图生图、编辑和变体接口尚未实现。

</div>
