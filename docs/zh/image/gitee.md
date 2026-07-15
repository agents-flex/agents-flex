<div v-pre>

# Gitee AI 图片生成与编辑

`agents-flex-image-gitee` 适配 Gitee AI 模力方舟的同步图片接口。公共层始终调用 `generate()`：未提供输入图片时执行文生图，提供一张输入图片时执行图片编辑。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 文本生成图片

```java
GiteeImageModelConfig config = new GiteeImageModelConfig();
config.setApiKey(System.getenv("GITEE_AI_API_KEY"));
config.setModel(GiteeImageModels.FLUX_1_DEV);

GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("雨后的江南街巷，电影感，自然光");
request.setSize(1024, 1024);
request.setN(1);
request.setResponseFormat("url");

ImageResponse response = new GiteeImageModel(config).generate(request);
```

请求会同步发送到 `/v1/images/generations`，支持 `model`、`prompt`、`size`、`user`、`n` 和 `response_format`。`n` 的有效范围为 1 到 4，返回格式可以是 `url` 或 `b64_json`。

## 图片编辑

给请求增加一张 `inputImages` 后，同一个 `generate()` 会自动改用 `/v1/images/edits` 和 multipart 请求：

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setModel(GiteeImageModels.QWEN_IMAGE_EDIT);
request.setPrompt("把天空替换成晚霞，保留建筑结构");
request.addInputImage(Image.ofUrl("https://example.com/source.png"));
request.setN(1);

ImageResponse response = new GiteeImageModel(config).generate(request);
```

输入图片可以是远程 URL、字节或 Base64：

```java
request.addInputImage(Image.ofBytes(imageBytes, "image/png"));
```

Gitee 编辑接口当前只接受一张输入图片，并且 `n` 只能为 1。

## 掩膜编辑

掩膜属于供应商参数，通过 `options` 传入。可以使用 `Image`、URL、`File`、`InputStream` 或 `byte[]`：

```java
request.addOption(GiteeImageModel.OPTION_MASK,
    Image.ofBytes(maskPngBytes, "image/png"));
```

掩膜应为 PNG，大小不超过 4 MB，并与原图分辨率一致。透明通道标识编辑区域。

DreamO 模型的任务类型同样通过 `options` 传入：

```java
request.addOption(GiteeImageModel.OPTION_TASK_TYPES,
    Collections.singletonList("style"));
```

可选值为 `ip`、`id` 或 `style`。

## 配置

默认配置如下：

| 配置 | 默认值 |
| --- | --- |
| `endpoint` | `https://ai.gitee.com` |
| `requestPath` | `/v1/images/generations` |
| `editPath` | `/v1/images/edits` |
| `model` | `flux-1-schnell` |

单次请求通过 `request.setModel(...)` 设置的模型优先于 Config。其他 Gitee 专属字段可以通过 `request.addOption(key, value)` 透传。

</div>
