<div v-pre>

# OpenAI 与兼容图片服务

OpenAI、Gitee AI 和百度千帆适配器都提供 Bearer Token 鉴权的图片调用。它们的 Config 和默认模型不同，需使用对应模块。

## OpenAI

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-openai</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
OpenAIImageModelConfig config = new OpenAIImageModelConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
// config.setEndpoint("https://your-openai-compatible-host");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("A red paper plane above a futuristic city at sunrise");
request.setSize(1024, 1024);
request.setN(1);

ImageResponse response = new OpenAIImageModel(config).generate(request);
```

默认端点是 `https://api.openai.com`，默认模型是 `dall-e-3`，请求发送到 `/v1/images/generations`。

## Gitee AI

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
GiteeImageModelConfig config = new GiteeImageModelConfig();
config.setApiKey(System.getenv("GITEE_AI_API_KEY"));
config.setModel(GiteeImageModels.FLUX_1_SCHNELL);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一只小老虎站在高速列车里");
request.setSize(1024, 1024);

ImageResponse response = new GiteeImageModel(config).generate(request);
```

默认端点是 `https://ai.gitee.com`，请求发送到 `/v1/images/generations`。

Gitee AI 还支持同步图片编辑。向 `GenerateImageRequest` 添加一张 `inputImages` 后，适配器会自动调用 multipart 格式的 `/v1/images/edits`。完整参数见 [Gitee AI 图片生成与编辑](./gitee)。

## 百度千帆

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-qianfan</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
QianfanImageModelConfig config = new QianfanImageModelConfig();
config.setApiKey(System.getenv("QIANFAN_API_KEY"));
config.setModels("irag-1.0");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("现代办公室里的职场人像，自然光");

ImageResponse response = new QianfanImageModel(config).generate(request);
```

默认端点是 `https://qianfan.baidubce.com/v2/images/generations`。`headersConfig` 可增加自定义 Header。

## 能力边界

三个适配器都支持文生图。Gitee AI 还通过统一的同步 `generate()` 支持图片编辑；是否进入编辑模式由 `inputImages` 决定。不同供应商和模型的输入图数量、掩膜及输出数量限制并不相同，应用层应结合对应适配器文档进行校验。

</div>
