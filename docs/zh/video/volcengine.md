<div v-pre>

# 火山引擎视频生成开发文档

## 概述

`agents-flex-video-volcengine` 是 Agents-Flex 对火山引擎方舟视频生成模型的适配模块，使用方舟内容生成异步任务 API。

当前适配器支持：

- 文生视频
- 单图生视频
- 首尾帧生视频
- 多参考图输入
- 源视频输入
- 音频输入与有声视频生成
- 分辨率、画幅、时长、Seed、固定镜头和水印等参数
- 方舟请求顶层参数透传

参考：[火山引擎视频生成 API](https://docs.volcengine.com/docs/82379/1520757?lang=zh)

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-volcengine</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
import com.agentsflex.video.volcengine.VolcengineVideoModelConfig;
import com.agentsflex.video.volcengine.VolcengineVideoModels;

VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();
config.setApiKey(System.getenv("ARK_API_KEY"));
config.setModel(VolcengineVideoModels.SEEDANCE_2_0);
```

默认配置：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoint` | `https://ark.cn-beijing.volces.com` | 方舟服务地址 |
| `requestPath` | `/api/v3/contents/generations/tasks` | 创建异步任务 |
| `queryPath` | `/api/v3/contents/generations/tasks/{taskId}` | 查询任务 |
| `model` | `doubao-seedance-2-0-260128` | 默认 Seedance 2.0 模型 |
| `pollIntervalMillis` | `10000` | 默认 10 秒查询一次 |
| `timeoutMillis` | `600000` | 默认最多等待 10 分钟 |

## 支持的模型常量

| 常量 | 模型名称 |
| --- | --- |
| `SEEDANCE_2_0` | `doubao-seedance-2-0-260128` |
| `SEEDANCE_1_5_PRO` | `doubao-seedance-1-5-pro-251215` |
| `SEEDANCE_1_0_PRO` | `doubao-seedance-1-0-pro-250528` |
| `SEEDANCE_1_0_LITE` | `doubao-seedance-1-0-lite-t2v-250428` |

模型标识可能随火山引擎更新。也可以直接设置新的模型名称：

```java
config.setModel("new-seedance-model");
// 或只覆盖本次请求
request.setModel("new-seedance-model");
```

## 文生视频

```java
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.agentsflex.video.volcengine.VolcengineVideoModel;
import com.agentsflex.video.volcengine.VolcengineVideoModelConfig;
import com.agentsflex.video.volcengine.VolcengineVideoModels;

import java.io.File;

VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();
config.setApiKey(System.getenv("ARK_API_KEY"));
config.setModel(VolcengineVideoModels.SEEDANCE_2_0);

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt(
    "一架红色纸飞机滑翔在日出时安静的未来城市上空，" +
    "电影感广角镜头，平滑镜头运动，真实光照"
);
request.setDuration(5);
request.setResolution("720p");
request.setAspectRatio("16:9");
request.setGenerateAudio(false);
request.setWatermark(false);

VolcengineVideoModel videoModel = new VolcengineVideoModel(config);
VideoResponse response = videoModel.generateAndWait(request);

if (response.getStatus() == VideoTaskStatus.SUCCEEDED) {
    response.getVideo().writeToFile(new File("output/volcengine-video.mp4"));
} else {
    throw new IllegalStateException(
        response.getErrorCode() + ": " + response.getErrorMessage()
    );
}
```

## 多模态 `content`

方舟使用 `content` 数组组织文本、图片、视频和音频。适配器会根据统一请求自动构造该数组。

### 文本

```java
request.setPrompt("镜头缓慢向前移动，城市灯光逐渐亮起");
```

映射结果：

```json
{
  "type": "text",
  "text": "镜头缓慢向前移动，城市灯光逐渐亮起"
}
```

### 首帧

```java
request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
```

映射结果：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "https://example.com/first.png"
  },
  "role": "first_frame"
}
```

### 尾帧

```java
request.setLastFrame(Image.ofUrl("https://example.com/last.png"));
```

尾帧使用 `role: last_frame`。

### 参考图片

```java
request.addReferenceImage(Image.ofUrl("https://example.com/character.png"));
request.addReferenceImage(Image.ofUrl("https://example.com/style.png"));
```

每张参考图片分别添加到 `content`，并使用 `role: reference_image`。

### 源视频

```java
import com.agentsflex.core.model.video.Video;

request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));
```

映射为 `type: video_url`。

### 音频

```java
request.setAudioUrl("https://example.com/audio.wav");
```

映射为 `type: audio_url`。

## 请求字段映射

| 统一字段 | 方舟字段 |
| --- | --- |
| `model` | `model` |
| `prompt` | `content[]` 中的 `text` |
| `firstFrame` | `content[]` 中 `role: first_frame` 的 `image_url` |
| `lastFrame` | `content[]` 中 `role: last_frame` 的 `image_url` |
| `referenceImages` | `content[]` 中 `role: reference_image` 的 `image_url` |
| `sourceVideo.url` | `content[]` 中的 `video_url` |
| `audioUrl` | `content[]` 中的 `audio_url` |
| `duration` | `duration` |
| `resolution` | `resolution` |
| `aspectRatio` | `ratio` |
| `fps` | `fps` |
| `seed` | `seed` |
| `watermark` | `watermark` |
| `cameraFixed` | `camera_fixed` |
| `generateAudio` | `generate_audio` |

## 扩展参数

火山引擎适配器将 `options` 直接合并到请求顶层，可用于服务商新增但统一请求尚未提供的参数。

```java
request.addOption("callback_url", "https://example.com/video/callback");
request.addOption("return_last_frame", true);
```

扩展参数最后合并，因此同名参数可以覆盖统一字段生成的值：

```java
request.setDuration(5);
request.addOption("duration", 10); // 最终请求使用 10
```

覆盖统一字段时应确保符合目标模型协议。

## 异步任务

创建任务使用：

```http
POST /api/v3/contents/generations/tasks
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

查询任务使用：

```http
GET /api/v3/contents/generations/tasks/{taskId}
Authorization: Bearer YOUR_API_KEY
```

方舟任务状态映射：

| 方舟状态 | Agents-Flex 状态 |
| --- | --- |
| `queued` / `pending` | `QUEUED` |
| `running` / `processing` | `RUNNING` |
| `succeeded` / `success` | `SUCCEEDED` |
| `failed` | `FAILED` |
| `canceled` / `cancelled` | `CANCELED` |

## 非阻塞任务示例

```java
VideoResponse submitted = videoModel.generate(request);

if (submitted.isError()) {
    throw new IllegalStateException(submitted.getErrorMessage());
}

String taskId = submitted.getTaskId();
VideoResponse latest = videoModel.getResult(taskId);
```

## 真实集成测试

模块包含默认跳过的真实集成测试。仅当设置 `ARK_API_KEY` 时才会调用云端并产生费用：

```bash
export ARK_API_KEY="your-api-key"

mvn -pl agents-flex-video/agents-flex-video-volcengine \
    -am \
    -Dtest=VolcengineVideoModelIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
```

成功后视频保存到：

```text
agents-flex-video/agents-flex-video-volcengine/target/volcengine-generated.mp4
```

没有环境变量时测试自动跳过，不会创建云端任务。

## 注意事项

- API Key 不应写入源码、配置仓库或测试报告
- 图片、视频和音频 URL 必须满足方舟对公网访问和格式的要求
- 不同 Seedance 模型支持的输入类型、尺寸、时长和参数不同
- 生成时间受模型、分辨率、视频时长和服务商队列影响
- 返回的视频 URL 通常为临时签名地址，应及时下载
- `TIMED_OUT` 只表示本地等待超时，不代表方舟任务失败
- 视频生成会产生云服务费用，自动化测试应显式设置环境变量后再运行

</div>
