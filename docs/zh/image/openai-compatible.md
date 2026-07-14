<div v-pre>

# OpenAI 与兼容图片服务

OpenAI、Gitee AI 和百度千帆适配器都提供 Bearer Token 鉴权的文生图调用。它们的 Config 和默认模型不同，需使用对应模块。

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
config.setModel("flux-1-schnell");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一只小老虎站在高速列车里");
request.setSize(1024, 1024);

ImageResponse response = new GiteeImageModel(config).generate(request);
```

默认端点是 `https://ai.gitee.com`，请求发送到 `/v1/images/generations`。

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

三个适配器当前都支持文生图。OpenAI 和 Gitee 对编辑、变体会抛出 `UnsupportedOperationException`；其他未实现接口可能返回 `null`。应用层应显式限制可用操作。

</div>
