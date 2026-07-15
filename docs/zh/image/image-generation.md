<div v-pre>

# 图片模块核心设计

Agents-Flex 使用同一个 `ImageModel.generate()` 表达文生图、参考图生成、多图融合和图片编辑。请求是否包含输入图决定具体场景，供应商适配器负责转换为各自协议。

## 核心对象

| 类型 | 职责 |
| --- | --- |
| `ImageModel` | 同步生成图片 |
| `GenerateImageRequest` | 提示词、输入图、输出尺寸及公共生成参数 |
| `ImageResponse` | 图片、错误和原始元数据 |
| `BaseImageConfig` | 连接信息、模型能力和轮询配置 |
| `Image` | URL、Base64 或字节图片，并支持写入文件 |

图片模块只保留一个生成入口：`generate(GenerateImageRequest)`。文生图、参考图生成、多图融合、局部编辑和变体均通过请求内容表达。

## 统一请求

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("保留主体，改成杂志封面风格");
request.setNegativePrompt("模糊、文字变形");
request.addInputImage(Image.ofUrl("https://example.com/subject.png"));
request.addInputImage(Image.ofUrl("https://example.com/background.png"));
request.setResolution("2K");
request.setN(2);
request.setSeed(42);
request.setWatermark(false);
request.setPromptExtend(true);

ImageResponse response = imageModel.generate(request);
```

常用字段：

| 字段 | 说明 |
| --- | --- |
| `model` | 覆盖 Config 中的默认模型 |
| `prompt` / `negativePrompt` | 正向和反向提示词 |
| `inputImages` | 有序输入图；用于编辑、融合或参考生成 |
| `boundingBoxes` | 与输入图按索引对齐的局部编辑矩形区域 |
| `n` | 输出图片数量，受具体模型限制 |
| `width` / `height` / `sizeString` | 像素尺寸或供应商尺寸字符串 |
| `resolution` | `1K`、`2K`、`4K` 等分辨率档位 |
| `seed` / `watermark` / `promptExtend` | 通用生成控制项 |
| `sequentialGeneration` / `maxImages` | 组图或连续图片生成 |
| `options` | 尚未统一抽象的供应商参数 |

## 同步调用语义

图片模块在架构层面只提供同步调用。`generate()` 返回时必须已经获得最终图片或明确错误：

```java
ImageResponse response = imageModel.generate(request);
if (response.isError()) {
    throw new IllegalStateException(response.getErrorMessage());
}
response.getImage().writeToFile(new File("output/result.png"));
```

如果供应商只提供异步接口，对应适配器负责在 `generate()` 内部提交任务、轮询并解析最终结果。任务 ID 和任务状态不会暴露到公共图片 API。

## 模型能力

继承 `BaseImageConfig` 的适配器会声明文生图、图片编辑、多图输入、多图输出、负向提示词、水印等能力。能力字段用于调用前判断和 UI 展示，不会自动放宽具体模型的参数限制。

## 扩展参数

普通 `options` 默认由适配器放入供应商参数区。百炼还支持三个作用域：

```java
request.addOption("parameters", Collections.singletonMap("thinking_mode", true));
request.addOption("input", Collections.singletonMap("custom_input", "value"));
request.addOption("topLevel", Collections.singletonMap("custom", "value"));
```

云服务返回的 URL 通常只有 24 小时有效期，应及时下载或转存。

</div>
