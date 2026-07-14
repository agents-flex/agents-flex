<div v-pre>

# 硅基流动图片生成

`agents-flex-image-siliconflow` 适配硅基流动文生图 API，支持提示词、负向提示词、批量数量、尺寸、推理步数和引导系数。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-siliconflow</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 创建模型

```java
import com.agentsflex.image.siliconflow.SiliconImageModel;
import com.agentsflex.image.siliconflow.SiliconflowImageModelConfig;
import com.agentsflex.image.siliconflow.SiliconflowImageModels;

SiliconflowImageModelConfig config = new SiliconflowImageModelConfig();
config.setApiKey(System.getenv("SILICONFLOW_API_KEY"));
config.setModel(SiliconflowImageModels.Stable_Diffusion_XL);
config.setNumInferenceSteps(30);
config.setGuidanceScale(7);

SiliconImageModel imageModel = new SiliconImageModel(config);
```

默认服务地址为 `https://api.siliconflow.cn`。`SiliconflowImageModels` 提供已知模型 ID 及请求路径映射。

## 生成图片

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("A cute tiger standing inside a high-speed train");
request.setNegativePrompt("blurry, distorted, low quality");
request.setSize(1024, 1024);
request.setN(4);
request.addOption("num_inference_steps", 28);
request.addOption("guidance_scale", 6);

ImageResponse response = imageModel.generate(request);
```

请求中的 `num_inference_steps` 和 `guidance_scale` 会覆盖 Config 默认值。`n` 映射为 `batch_size`，未设置时为 `1`。

## 能力边界

当前实现文生图 `generate()`。图生图、编辑和变体接口尚未实现。

</div>
