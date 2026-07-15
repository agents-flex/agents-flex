<div v-pre>

# 阿里云百炼图片模型

`agents-flex-image-aliyun` 统一支持千问文生图、千问图片编辑、万相文生图 V2 和万相 2.7 图片生成与编辑。公共 API 始终同步返回；必须使用异步协议的模型由适配器在内部完成轮询。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
AliyunImageModelConfig config = new AliyunImageModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
ImageModel imageModel = new AliyunImageModel(config);
```

默认端点为 `https://dashscope.aliyuncs.com`。使用业务空间专属域名时直接设置 `endpoint`，请求路径会保持不变。

## 千问文生图

`qwen-image`、`qwen-image-plus` 等旧图片合成模型使用异步任务协议：

```java
config.setModel(AliyunImageModels.QWEN_IMAGE_PLUS);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一张中文活版印刷风格的春季海报");
request.setNegativePrompt("文字模糊、笔画错误");
request.setSize(1664, 928);
request.setPromptExtend(true);
request.setWatermark(false);

ImageResponse result = imageModel.generate(request);
```

适配器会向 `/api/v1/services/aigc/text2image/image-synthesis` 提交任务，并在内部通过 `/api/v1/tasks/{taskId}` 轮询，直到返回最终图片。任务 ID 不会暴露给调用方。

## 千问图片编辑

千问编辑使用同步多模态协议，支持 1-3 张输入图；部分模型支持输出 1-6 张图：

```java
config.setModel(AliyunImageModels.QWEN_IMAGE_2_0_PRO);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("使用图一作为底图，把图二中的角色放到建筑前方");
request.addInputImage(Image.ofUrl("https://example.com/city.png"));
request.addInputImage(Image.ofUrl("https://example.com/character.png"));
request.setSize(2048, 2048);
request.setN(2);

ImageResponse response = imageModel.generate(request);
```

千问编辑使用供应商的同步 HTTP 接口。

## 万相文生图 V2

`wan2.6-t2i` 直接使用同步 HTTP 协议：

```java
config.setModel(AliyunImageModels.WAN_2_6_T2I);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("一间有精致木门和落地窗的花店");
request.setSize(1280, 1280);
request.setN(1);

ImageResponse response = imageModel.generate(request);
```

万相 2.5 及以下只有异步协议，适配器会在 `generate()` 内部等待任务完成。

## 万相 2.7 生成与编辑

万相 2.7 使用同一模型完成文生图、多图参考、编辑和组图生成，最多可输入 9 张图片：

```java
config.setModel(AliyunImageModels.WAN_2_7_IMAGE_PRO);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("保持产品造型，生成一组统一视觉风格的广告图");
request.addInputImage(Image.ofUrl("https://example.com/product.png"));
request.setResolution("2K");
request.setSequentialGeneration(true);
request.setN(4);
request.addOption("thinking_mode", true);

ImageResponse response = imageModel.generate(request);
```

交互式框选通过核心请求直接表达，外层列表与输入图片按索引对齐：

```java
request.setBoundingBoxes(Arrays.asList(
    Arrays.asList(ImageBoundingBox.of(10, 20, 300, 400)),
    Collections.emptyList()
));
```

百炼适配器会将其转换为 `bbox_list`。每张输入图最多支持 2 个框，没有框的图片必须保留空列表。`wan2.7-image-pro` 的 4K 仅适用于无输入图、非组图的文生图场景，编辑与组图最高为 2K。

## 协议选择

| 模型族 | 适配器内部协议 |
| --- | --- |
| `qwen-image` / `qwen-image-plus` / `qwen-image-max` | 异步图片合成 |
| `qwen-image-edit*` / `qwen-image-2.0*` | 同步多模态 |
| `wan2.5` 及以下文生图 | 异步图片生成 |
| `wan2.6-t2i` / `wan2.7-image*` | 同步多模态 |

</div>
