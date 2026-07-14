<div v-pre>

# 视频生成开发文档

## 概述

Agents-Flex 视频模块为不同云服务商的视频生成模型提供统一的 Java API。目前支持：

- **阿里云百炼**：通义万相 Wan 和 HappyHorse 系列视频生成与编辑模型
- **火山引擎方舟**：豆包 Seedance 系列视频生成模型

视频生成通常需要数十秒到数分钟，因此模块采用异步任务设计：先提交任务获得任务 ID，再查询任务状态，成功后获取视频地址。框架同时提供阻塞轮询方法，适用于脚本、离线任务和简单业务场景。

### 核心价值

- **统一抽象**：使用同一套 `VideoModel`、请求、响应和状态类型接入多个服务商
- **多场景输入**：支持文生视频、图生视频、首尾帧、参考图、视频编辑和音频输入
- **异步任务统一**：屏蔽不同服务商的任务状态和查询接口差异
- **模型灵活切换**：Config 提供默认模型，请求也可以临时指定模型
- **参数可扩展**：统一字段之外的模型新参数可通过 `options` 透传
- **结果易于保存**：支持从临时 URL 下载视频并直接写入本地文件

## 模块组成

```text
agents-flex-core
└── com.agentsflex.core.model.video
    ├── VideoModel
    ├── BaseVideoModel
    ├── BaseVideoConfig
    ├── GenerateVideoRequest
    ├── VideoResponse
    ├── VideoTaskStatus
    └── Video

agents-flex-video
├── agents-flex-video-aliyun
├── agents-flex-video-gitee
└── agents-flex-video-volcengine
```

### 核心类

| 类名 | 职责 |
| --- | --- |
| `VideoModel` | 定义提交任务、查询结果和阻塞等待方法 |
| `BaseVideoModel` | 保存服务商 Config，并提供使用默认轮询参数的等待方法 |
| `BaseVideoConfig` | 统一连接信息、模型能力、查询路径、轮询间隔和超时时间 |
| `GenerateVideoRequest` | 封装提示词、图片、视频、音频和生成参数 |
| `VideoResponse` | 封装任务 ID、统一状态、视频结果和错误信息 |
| `VideoTaskStatus` | 统一不同服务商的任务状态 |
| `Video` | 表示视频 URL、二进制内容、封面、时长和尺寸 |

## 核心接口

```java
public interface VideoModel {
    VideoResponse generate(GenerateVideoRequest request);

    VideoResponse getResult(String taskId);

    VideoResponse generateAndWait(
        GenerateVideoRequest request,
        long timeoutMillis,
        long pollIntervalMillis
    );
}
```

### 提交任务

`generate()` 只负责提交任务。调用成功通常表示服务商已经接受请求，并不代表视频已经生成完成。

```java
VideoResponse submitted = videoModel.generate(request);

if (submitted.isError()) {
    System.err.println(submitted.getErrorMessage());
    return;
}

String taskId = submitted.getTaskId();
```

### 查询结果

```java
VideoResponse result = videoModel.getResult(taskId);

if (result.getStatus() == VideoTaskStatus.SUCCEEDED) {
    Video video = result.getVideo();
}
```

### 阻塞等待

```java
VideoResponse result = videoModel.generateAndWait(
    request,
    10 * 60_000L,
    10_000L
);
```

使用 `BaseVideoModel` 的服务商实现还可以读取 Config 中的默认时间：

```java
config.setTimeoutMillis(10 * 60_000L);
config.setPollIntervalMillis(10_000L);

VideoResponse result = videoModel.generateAndWait(request);
```

> `generateAndWait()` 会阻塞当前线程。Web 服务中建议提交后保存任务 ID，通过定时任务、消息队列或前端轮询查询结果。

## 异步任务状态

| 状态 | 说明 | 是否终态 |
| --- | --- | --- |
| `SUBMITTED` | 请求已提交，尚未获得更具体状态 | 否 |
| `QUEUED` | 已进入服务商队列 | 否 |
| `RUNNING` | 服务商正在生成视频 | 否 |
| `SUCCEEDED` | 生成成功，通常包含视频结果 | 是 |
| `FAILED` | 服务商执行失败 | 是 |
| `CANCELED` | 任务已取消 | 是 |
| `TIMED_OUT` | 本地等待超时，云端任务可能仍在执行 | 是 |
| `UNKNOWN` | 未知或无法映射的状态 | 否 |

## 支持的生成场景

| 场景 | 主要请求字段 | 说明 |
| --- | --- | --- |
| 文生视频 | `prompt` | 仅通过文本描述生成视频 |
| 图生视频 | `prompt`、`firstFrame` | 以单张图片作为初始画面 |
| 首尾帧生视频 | `firstFrame`、`lastFrame` | 约束视频开始与结束画面 |
| 参考图生视频 | `referenceImages` | 约束人物、物体、场景或风格一致性 |
| 视频生视频 | `sourceVideo` | 视频编辑、重绘或风格迁移 |
| 音频驱动视频 | `audioUrl` | 使用音频驱动动作、口型或节奏 |
| 有声视频生成 | `generateAudio` | 由支持的模型同时生成音频 |

具体能力取决于服务商和模型。切换模型前应确认对应官方文档。

## 模型能力配置

所有服务商 Config 均继承 `BaseVideoConfig`。能力字段描述 Config 当前默认模型：

```java
config.isSupportTextToVideo();
config.isSupportImageToVideo();
config.isSupportFirstLastFrame();
config.isSupportReferenceImages();
config.isSupportVideoToVideo();
config.isSupportAudioInput();
config.isSupportAudioGeneration();
config.isSupportNegativePrompt();
config.isSupportPromptExtend();
```

Config 还可以描述模型限制：

```java
config.getMaxReferenceImages();
config.getSupportedDurations();
config.getSupportedResolutions();
config.getSupportedAspectRatios();
```

这些字段用于能力展示和调用前判断，不会自动修改或拒绝请求。通过 `request.setModel(...)` 临时切换模型时，应以目标模型能力为准。

## 请求参数

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `model` | `String` | 请求级模型；为空时使用 Config 默认模型 |
| `prompt` | `String` | 正向提示词 |
| `negativePrompt` | `String` | 需要避免的内容或质量问题 |
| `firstFrame` | `Image` | 图生视频输入图或首帧 |
| `lastFrame` | `Image` | 首尾帧模式的尾帧 |
| `referenceImages` | `List<Image>` | 参考图片列表 |
| `sourceVideo` | `Video` | 视频编辑或视频生视频的源视频 |
| `audioUrl` | `String` | 输入音频 URL |
| `duration` | `Integer` | 视频时长，单位为秒 |
| `width` / `height` | `Integer` | 输出宽高，单位为像素 |
| `resolution` | `String` | 分辨率档位，如 `720p` |
| `aspectRatio` | `String` | 画幅，如 `16:9`、`9:16` |
| `fps` | `Integer` | 每秒帧数 |
| `seed` | `Integer` | 随机种子 |
| `watermark` | `Boolean` | 是否添加服务商水印 |
| `promptExtend` | `Boolean` | 是否启用提示词智能扩写 |
| `generateAudio` | `Boolean` | 是否同时生成音频 |
| `cameraFixed` | `Boolean` | 是否固定摄像机 |
| `options` | `Map<String, Object>` | 服务商和模型特有参数 |

## 视频结果

```java
Video video = result.getVideo();

System.out.println(video.getUrl());
System.out.println(video.getDuration());
System.out.println(video.getWidth() + "x" + video.getHeight());

video.writeToFile(new File("output.mp4"));
```

服务商返回的 URL 通常具有有效期。需要长期保存时，应在任务成功后及时下载或转存到自己的对象存储。

## 错误处理

```java
if (response == null) {
    throw new IllegalStateException("服务商未返回响应");
}

if (response.isError()) {
    System.err.println("错误码: " + response.getErrorCode());
    System.err.println("错误信息: " + response.getErrorMessage());
}
```

服务商原始响应会保存在 `VideoResponse` 的元数据中，可用于日志、用量统计和问题排查。

## 下一步

- [视频生成快速开始](./getting-started)
- [阿里云视频生成](./aliyun)
- [HappyHorse 视频生成](./happyhorse)
- [火山引擎视频生成](./volcengine)

</div>
