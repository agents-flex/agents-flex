<div v-pre>

# 图片生成快速开始

## 5 分钟快速上手

本示例使用 Gitee AI 生成一张图片。切换服务商时，主要替换 Maven 依赖、Config 和 `ImageModel` 实现。

## 前置要求

- Java 8 或更高版本
- Maven 3.6+
- 已开通对应图片生成服务并获得 API Key

## 第一步：添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

| 服务商 | `artifactId` |
| --- | --- |
| 阿里云百炼 | `agents-flex-image-bailian` |
| OpenAI | `agents-flex-image-openai` |
| 百度千帆 | `agents-flex-image-qianfan` |
| 硅基流动 | `agents-flex-image-siliconflow` |
| Stability AI | `agents-flex-image-stability` |
| 腾讯混元 | `agents-flex-image-tencent` |
| 火山引擎 | `agents-flex-image-volcengine` |

## 第二步：配置密钥

```bash
export GITEE_AI_API_KEY="your-token"
```

## 第三步：生成图片

```java
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.gitee.GiteeImageModel;
import com.agentsflex.image.gitee.GiteeImageModelConfig;

GiteeImageModelConfig config = new GiteeImageModelConfig();
config.setApiKey(System.getenv("GITEE_AI_API_KEY"));
config.setModel("flux-1-schnell");

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("雨后的竹林小路，柔和自然光，电影感");
request.setSize(1024, 1024);
request.setN(1);

ImageResponse response = new GiteeImageModel(config).generate(request);
```

## 第四步：保存结果

```java
import java.io.File;

if (response == null || response.isError() || response.getImages().isEmpty()) {
    throw new IllegalStateException(
        response == null ? "未返回响应" : response.getErrorMessage()
    );
}

response.getImages().get(0).writeToFile(
    new File("output/generated-image.png")
);
```

## 常见问题

### 图片 URL 为什么无法长期访问？

服务商通常返回临时签名 URL。生成成功后应及时调用 `writeToFile()` 或转存到对象存储。

### 尺寸参数为什么报错？

每个模型只接受特定的像素尺寸或档位。某些模型使用 `setSize(1024, 1024)`，阿里云部分模型使用 `setSizeString("1k")`。

### 可以直接切换模型 ID 吗？

可以通过 Config 设置默认模型，部分适配器也支持 `request.setModel(...)`。但参数能力不会自动转换，切换后需重新核对尺寸和生成选项。

## 下一步

- [图片生成核心概念](./image-generation)
- [阿里云百炼](./aliyun)
- [OpenAI 与兼容服务](./openai-compatible)

</div>
