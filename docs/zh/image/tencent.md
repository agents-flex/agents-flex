<div v-pre>

# 腾讯混元图片生成

`agents-flex-image-tencent` 使用腾讯云 TC3-HMAC-SHA256 签名调用混元文生图服务。鉴权需要 SecretId 和 SecretKey。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-image-tencent</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置凭据

```bash
export TENCENT_SECRET_ID="your-secret-id"
export TENCENT_SECRET_KEY="your-secret-key"
```

```java
TencentImageModelConfig config = new TencentImageModelConfig();
config.setApiKey(System.getenv("TENCENT_SECRET_KEY"));
config.setApiSecret(System.getenv("TENCENT_SECRET_ID"));
config.setRegion("ap-guangzhou");

TencentImageModel imageModel = new TencentImageModel(config);
```

## 生成图片

```java
GenerateImageRequest request = new GenerateImageRequest();
request.setPrompt("雨中的竹林小路，写实风格");
request.setNegativePrompt("模糊，低质量");
request.setN(1);
request.addOption("LogoAdd", 0);

ImageResponse response = imageModel.generate(request);
```

| Config 字段 | 默认值 |
| --- | --- |
| `endpoint` | `https://hunyuan.tencentcloudapi.com` |
| `region` | `ap-guangzhou` |
| `service` | `hunyuan` |

> 当前实现中，`apiKey` 用于计算签名（对应 SecretKey），`apiSecret` 写入 Credential（对应 SecretId）。请按上述示例配置。

适配器先调用 `SubmitHunyuanImageJob`，再轮询 `QueryHunyuanImageJob`，API 版本为 `2023-09-01`。生成结果以 URL 形式写入 `ImageResponse`。

## 能力边界

当前文档覆盖 `generate()` 文生图。图生图、编辑和变体接口尚未实现。

</div>
