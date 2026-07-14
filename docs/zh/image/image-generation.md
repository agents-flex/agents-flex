<div v-pre>

# 图片生成开发文档

## 概述

Agents-Flex 图片模块为不同服务商的图片生成模型提供统一 Java API。应用层面向 `ImageModel` 编程，通过更换依赖、Config 和实现类切换服务商。

当前包含阿里云百炼、Gitee AI、OpenAI、百度千帆、阿里云 Qwen SDK、硅基流动、Stability AI、腾讯混元和火山引擎适配器。

## 核心类

| 类名 | 职责 |
| --- | --- |
| `ImageModel` | 定义文生图、图生图、编辑和变体接口 |
| `GenerateImageRequest` | 封装提示词、尺寸、数量、参考图和扩展参数 |
| `EditImageRequest` | 在生成请求上增加待编辑图和遮罩 |
| `VaryImageRequest` | 封装图片变体请求 |
| `ImageResponse` | 返回图片列表、错误信息和元数据 |
| `Image` | 表示 URL、Base64 或字节图片，并支持写入文件 |

## 核心接口

```java
public interface ImageModel {
    ImageResponse generate(GenerateImageRequest request);
    ImageResponse img2imggenerate(GenerateImageRequest request);
    ImageResponse edit(EditImageRequest request);
    ImageResponse vary(VaryImageRequest request);
}
```

`generate()` 是各适配器的主要入口。其他方法是统一扩展点，当前仅部分适配器实现；调用前应查看对应服务商文档。

## 生成请求

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("雨后的竹林小路，柔和自然光，高细节");
request.setNegativePrompt("模糊，低清晰度，畸变");
request.setSize(1024, 1024);
request.setN(1);
request.setModel("provider-model-id");
request.addOption("seed", 42);
```

| 字段 | 说明 |
| --- | --- |
| `model` | 请求级模型；是否优先于 Config 取决于适配器 |
| `prompt` / `negativePrompt` | 正向和负向提示词 |
| `n` | 期望返回的图片数量 |
| `width` / `height` | 像素尺寸，`getSizeString()` 会组装为 `1024x1024` |
| `sizeString` | 服务商尺寸档位，如 `1k`；设置后优先使用 |
| `quality` / `style` | 质量和风格标识，需适配器支持 |
| `refImages` | 参考图列表，主要用于图生图 |
| `options` | 服务商特有参数 |

## 处理结果

```java
ImageResponse response = imageModel.generate(request);
if (response == null || response.isError()) {
    String message = response == null ? "服务商未返回响应" : response.getErrorMessage();
    throw new IllegalStateException(message);
}

for (int i = 0; i < response.getImages().size(); i++) {
    response.getImages().get(i).writeToFile(
        new File("output/generated-" + i + ".png")
    );
}
```

`Image.writeToFile()` 会按字节、Base64、URL 的顺序取得内容。云服务返回的 URL 通常有效期较短，生产环境应及时下载或转存。

## 使用建议

- API Key 通过环境变量或密钥管理服务注入。
- 每个模型支持的尺寸、数量和参数不同，以服务商文档为准。
- 结果可能是 URL 或二进制内容，不要只判断 `getUrl()`。
- 图片生成会产生费用，集成测试应默认跳过真实请求。

## 下一步

- [快速开始](./getting-started)
- [阿里云百炼](./aliyun)
- [OpenAI 与兼容服务](./openai-compatible)
- [火山引擎](./volcengine)

</div>
