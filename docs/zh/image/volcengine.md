<div v-pre>

# 火山引擎图片生成

`agents-flex-image-volcengine` 通过火山引擎方舟 Ark Images SDK 生成图片，支持文生图和携带一张或多张参考图的生成请求。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-volcengine</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置凭据

```bash
export VOLCENGINE_ACCESS_KEY="your-access-key"
export VOLCENGINE_SECRET_KEY="your-secret-key"
```

```java
VolcengineImageModelConfig config = new VolcengineImageModelConfig();
config.setAccessKey(System.getenv("VOLCENGINE_ACCESS_KEY"));
config.setSecretKey(System.getenv("VOLCENGINE_SECRET_KEY"));

VolcengineImageModel imageModel = new VolcengineImageModel(config);
```

## 文生图

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setModel("your-image-endpoint-or-model-id");
request.setPrompt("一座清晨雾中的未来城市");
request.setSize(1024, 1024);

ImageResponse response = imageModel.generate(request);
```

`model` 是必需字段，应填写已开通的图片模型 ID 或推理接入点 ID。适配器强制使用 URL 响应格式，并将返回地址写入 `ImageResponse`。

## 参考图生成

```java
import com.agentsflex.core.model.image.Image;
import java.util.Arrays;

GenerateImageRequest request = new GenerateImageRequest();
request.setModel("your-image-endpoint-or-model-id");
request.setPrompt("保留人物特征，改为水彩插画风格");
request.setRefImages(Arrays.asList(
    Image.ofUrl("https://example.com/reference.png")
));

ImageResponse response = imageModel.img2imggenerate(request);
```

参考图也可使用字节数组：

```java
request.setRefImages(Arrays.asList(
    Image.ofBytes(imageBytes, "image/png")
));
```

字节图片会转换为 Data URI。使用多张参考图前，应确认目标模型支持的数量和输入格式。

## 当前参数映射

| Agents-Flex 字段 | Ark Images 字段 |
| --- | --- |
| `model` | `model` |
| `prompt` | `prompt` |
| `sizeString` 或 `width` + `height` | `size` |
| `refImages` | `image` |

当前适配器固定 `stream=false`、`watermark=true`、`responseFormat=Url`。`negativePrompt`、`n`、`quality`、`style` 和 `options` 尚未映射到 Ark SDK 请求。

## 能力边界

- `generate()` 和 `img2imggenerate()` 目前调用同一条 Ark Images 请求路径，是否图生图由 `refImages` 决定。
- `edit()` 和 `vary()` 当前会抛出 `UnsupportedOperationException`。
- 模型能力和尺寸限制以火山引擎方舟的相应模型文档为准。

</div>
