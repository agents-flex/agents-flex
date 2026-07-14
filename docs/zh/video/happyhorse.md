<div v-pre>

# HappyHorse 视频生成

## 概述

HappyHorse 是阿里云百炼提供的视频生成与编辑模型系列。它与通义万相视频模型共用百炼的鉴权、异步任务创建、状态查询和结果响应协议，因此由现有 `agents-flex-video-aliyun` 模块支持，不需要引入新的 Maven 模块。

HappyHorse 与 Wan 模型的主要差异是输入素材格式：HappyHorse 使用统一的 `input.media[]` 数组表示首帧、参考图和源视频。为避免根据模型名前缀隐式切换协议，HappyHorse 由独立的 `AliyunHappyHorseVideoModel` 实现。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 地域与服务地址

模型、Endpoint 和 API Key 必须属于同一地域。百炼建议使用业务空间专属域名：

| 地域 | Endpoint |
| --- | --- |
| 华北 2（北京） | `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com` |
| 新加坡 | `https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com` |
| 美国（弗吉尼亚） | `https://dashscope-us.aliyuncs.com` |
| 德国（法兰克福） | `https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com` |

北京地域配置示例：

```java
AliyunHappyHorseVideoModelConfig config = new AliyunHappyHorseVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setEndpoint(
    "https://" + System.getenv("DASHSCOPE_WORKSPACE_ID") +
    ".cn-beijing.maas.aliyuncs.com"
);
```

创建和查询路径由 Config 默认提供：

```text
POST /api/v1/services/aigc/video-generation/video-synthesis
GET  /api/v1/tasks/{taskId}
```

## 支持的模型

| 模型常量 | 模型名称 | 场景 |
| --- | --- | --- |
| `HAPPYHORSE_1_1_T2V` | `happyhorse-1.1-t2v` | 文生视频 |
| `HAPPYHORSE_1_0_T2V` | `happyhorse-1.0-t2v` | 文生视频 |
| `HAPPYHORSE_1_1_I2V` | `happyhorse-1.1-i2v` | 基于首帧的图生视频 |
| `HAPPYHORSE_1_0_I2V` | `happyhorse-1.0-i2v` | 基于首帧的图生视频 |
| `HAPPYHORSE_1_1_R2V` | `happyhorse-1.1-r2v` | 参考生视频 |
| `HAPPYHORSE_1_0_R2V` | `happyhorse-1.0-r2v` | 参考生视频 |
| `HAPPYHORSE_1_0_VIDEO_EDIT` | `happyhorse-1.0-video-edit` | 视频编辑 |

## 文生视频

```java
AliyunHappyHorseVideoModelConfig config = new AliyunHappyHorseVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt(
    "一座由硬纸板和瓶盖搭建的微型城市，在夜晚焕发生机，" +
    "一列硬纸板火车缓缓驶过"
);
request.setResolution("720P");
request.setAspectRatio("16:9");
request.setDuration(5);
request.setWatermark(false);
request.setSeed(42);

AliyunHappyHorseVideoModel videoModel = new AliyunHappyHorseVideoModel(config);
VideoResponse response = videoModel.generateAndWait(request);
```

生成的核心请求结构：

```json
{
  "model": "happyhorse-1.1-t2v",
  "input": {
    "prompt": "一座由硬纸板和瓶盖搭建的微型城市..."
  },
  "parameters": {
    "resolution": "720P",
    "ratio": "16:9",
    "duration": 5,
    "watermark": false,
    "seed": 42
  }
}
```

文生视频参数：

| 参数 | 范围或默认值 |
| --- | --- |
| `resolution` | `720P`、`1080P`，默认 `1080P` |
| `ratio` | `16:9`、`9:16`、`1:1`、`4:3`、`3:4`、`4:5`、`5:4`、`9:21`、`21:9` |
| `duration` | 3–15 秒，默认 5 秒 |
| `watermark` | 默认 `true`，水印文字为 Happy Horse |
| `seed` | 0–2147483647 |

## 基于首帧的图生视频

```java
request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_I2V);
request.setPrompt("一只猫在草地上奔跑"); // 可选
request.setFirstFrame(
    Image.ofUrl("https://example.com/first-frame.png")
);
request.setResolution("720P");
request.setDuration(5);
```

适配器自动转换为：

```json
{
  "input": {
    "prompt": "一只猫在草地上奔跑",
    "media": [
      {
        "type": "first_frame",
        "url": "https://example.com/first-frame.png"
      }
    ]
  }
}
```

首帧支持公网 URL 或 Data URI 格式的 Base64 图片。图生视频的输出宽高比自动跟随首帧，不支持 `ratio` 参数；即使请求设置了 `aspectRatio`，适配器也不会发送。

素材要求以服务商文档为准，主要限制包括：

- JPEG、JPG、PNG、WEBP
- 宽和高不小于 300 像素
- 宽高比 1:2.5～2.5:1
- 文件不超过 20 MB

## 参考生视频

```java
request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_R2V);
request.setPrompt(
    "[Image 1]中的人物拿起[Image 2]中的折扇，" +
    "镜头缓慢推近"
);
request.addReferenceImage(
    Image.ofUrl("https://example.com/person.jpg")
);
request.addReferenceImage(
    Image.ofUrl("https://example.com/fan.jpg")
);
request.setResolution("720P");
request.setAspectRatio("16:9");
request.setDuration(5);
```

适配器按照 `referenceImages` 的添加顺序生成 `media`：

```json
{
  "input": {
    "prompt": "[Image 1]中的人物拿起[Image 2]中的折扇...",
    "media": [
      {
        "type": "reference_image",
        "url": "https://example.com/person.jpg"
      },
      {
        "type": "reference_image",
        "url": "https://example.com/fan.jpg"
      }
    ]
  }
}
```

参考图片数量必须为 1–9 张。提示词中的 `[Image 1]`、`[Image 2]` 与列表顺序一一对应。

## 视频编辑

```java
request.setModel(AliyunVideoModels.HAPPYHORSE_1_0_VIDEO_EDIT);
request.setPrompt("让视频中的角色穿上参考图中的条纹毛衣");
request.setSourceVideo(
    Video.ofUrl("https://example.com/source.mp4")
);
request.addReferenceImage(
    Image.ofUrl("https://example.com/clothes.webp")
);
request.setResolution("720P");
```

适配器自动保证源视频排在参考图片之前：

```json
{
  "input": {
    "prompt": "让视频中的角色穿上参考图中的条纹毛衣",
    "media": [
      {
        "type": "video",
        "url": "https://example.com/source.mp4"
      },
      {
        "type": "reference_image",
        "url": "https://example.com/clothes.webp"
      }
    ]
  }
}
```

视频编辑要求：

- 必须有且仅有一个源视频 URL
- 可选 0–5 张参考图片
- 输入视频支持 MP4、MOV，建议 H.264
- 输入视频 3–60 秒，文件不超过 100 MB
- 输出最多 15 秒；输入超过 15 秒时从头截取

### 声音控制

`audio_setting` 是 HappyHorse 视频编辑特有参数，通过 `parameters` 扩展传入：

```java
Map<String, Object> parameters = new HashMap<>();
parameters.put("audio_setting", "origin");
request.addOption("parameters", parameters);
```

可选值：

- `auto`：由模型自行控制，默认值
- `origin`：保留输入视频原声

视频编辑不支持主动指定输出时长，因此适配器不会把 `request.duration` 发送给该模型。

## 参数映射差异

| 统一字段 | Wan 常用协议 | HappyHorse 协议 |
| --- | --- | --- |
| `firstFrame` | `input.img_url` | `input.media[{type:first_frame}]` |
| `referenceImages` | `input.reference_urls` | `input.media[{type:reference_image}]` |
| `sourceVideo` | `input.video_url` | `input.media[{type:video}]` |

该差异由 `AliyunHappyHorseVideoModel` 处理。调用方只需要选择正确的 HappyHorse 模型名称，不需要手动构造 `media`；Wan 模型则使用 `AliyunWanVideoModel`。

## 任务状态与结果

HappyHorse 复用百炼标准异步任务协议：

```text
PENDING → RUNNING → SUCCEEDED / FAILED
```

任务 ID 和结果 URL 的有效期均为 24 小时。成功后应及时下载或转存视频：

```java
if (response.getStatus() == VideoTaskStatus.SUCCEEDED) {
    response.getVideo().writeToFile(
        new File("output/happyhorse.mp4")
    );
}
```

建议轮询间隔为 15 秒。查询接口默认 RPS 为 20，不应高频轮询。

## 参数校验

适配器在发送请求前执行以下 HappyHorse 专用校验：

- T2V 不接受图片或视频素材
- I2V 必须且只能设置一张 `firstFrame`
- R2V 必须设置 1–9 张 `referenceImages`
- Video Edit 必须设置一个带 URL 的 `sourceVideo`，最多 5 张参考图
- HappyHorse 当前不支持 `lastFrame` 和统一 `audioUrl`

校验失败时返回错误码 `InvalidParameter`，不会创建云端任务。

## 离线单元测试

HappyHorse 请求映射测试不会访问云端或产生费用：

```bash
mvn -pl agents-flex-video/agents-flex-video-aliyun \
    -am \
    -Dtest=HappyHorseVideoModelTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
```

测试覆盖文生视频、首帧图生视频、参考生视频、视频编辑和参考图数量限制。

</div>
