<div v-pre>

# 阿里云视频生成开发文档

## 概述

`agents-flex-video-aliyun` 是 Agents-Flex 对阿里云百炼视频生成与编辑模型的适配模块，使用 DashScope 异步任务 API，同时支持通义万相和 HappyHorse 模型系列。

当前适配器支持：

- 文生视频
- 单图生视频
- 首尾帧生视频
- 参考图生视频
- 视频生视频与视频编辑输入
- 音频输入
- 提示词扩写、随机种子、水印等生成参数
- 服务商新参数透传

参考：[阿里云视频生成与编辑模型](https://help.aliyun.com/zh/model-studio/video-generate-edit-model)

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
import com.agentsflex.video.aliyun.AliyunWanVideoModelConfig;
import com.agentsflex.video.aliyun.AliyunVideoModels;

AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setModel(AliyunVideoModels.WAN_2_6_T2V);
```

默认配置：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoint` | `https://dashscope.aliyuncs.com` | DashScope 服务地址 |
| `requestPath` | `/api/v1/services/aigc/video-generation/video-synthesis` | 创建异步任务 |
| `queryPath` | `/api/v1/tasks/{taskId}` | 查询任务 |
| `model` | `wan2.6-t2v` | 默认文生视频模型 |
| `pollIntervalMillis` | `10000` | 默认 10 秒查询一次 |
| `timeoutMillis` | `600000` | 默认最多等待 10 分钟 |

如使用兼容代理或其他地域端点，可以修改：

```java
config.setEndpoint("https://your-endpoint.example.com");
config.setRequestPath("/api/v1/services/aigc/video-generation/video-synthesis");
config.setQueryPath("/api/v1/tasks/{taskId}");
```

## 支持的模型常量

| 常量 | 模型名称 | 典型场景 |
| --- | --- | --- |
| `WAN_2_6_T2V` | `wan2.6-t2v` | 文生视频 |
| `WAN_2_6_I2V` | `wan2.6-i2v` | 图生视频 |
| `WAN_2_6_R2V` | `wan2.6-r2v` | 参考图生视频 |
| `WAN_2_2_T2V_PLUS` | `wan2.2-t2v-plus` | 文生视频 |
| `WAN_2_2_I2V_PLUS` | `wan2.2-i2v-plus` | 图生视频 |
| `WAN_2_1_KF2V_PLUS` | `wanx2.1-kf2v-plus` | 首尾帧生视频 |

HappyHorse 模型使用相同的模块和异步任务 API，但输入素材采用 `input.media[]` 协议。完整模型列表和示例参见 [HappyHorse 视频生成](./happyhorse)。

模型名称和能力可能随服务商更新。也可以直接使用服务商发布的新模型名称：

```java
config.setModel("new-video-model");
// 或只对本次请求覆盖
request.setModel("new-video-model");
```

## 文生视频

```java
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.agentsflex.video.aliyun.AliyunWanVideoModel;
import com.agentsflex.video.aliyun.AliyunWanVideoModelConfig;
import com.agentsflex.video.aliyun.AliyunVideoModels;

import java.io.File;

AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setModel(AliyunVideoModels.WAN_2_6_T2V);

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt(
    "一架红色纸飞机飞过日出时的未来城市，电影感广角镜头，" +
    "平滑镜头运动，真实光照"
);
request.setNegativePrompt("模糊，抖动，畸变，低质量");
request.setSize(1280, 720);
request.setDuration(5);
request.setPromptExtend(true);
request.setWatermark(false);

AliyunWanVideoModel videoModel = new AliyunWanVideoModel(config);
VideoResponse response = videoModel.generateAndWait(request);

if (response.getStatus() == VideoTaskStatus.SUCCEEDED) {
    response.getVideo().writeToFile(new File("output/aliyun-video.mp4"));
} else {
    throw new IllegalStateException(
        response.getErrorCode() + ": " + response.getErrorMessage()
    );
}
```

## 图生视频

```java
import com.agentsflex.core.model.image.Image;

request.setModel(AliyunVideoModels.WAN_2_6_I2V);
request.setPrompt("镜头向前推进，纸飞机缓慢升空");
request.setFirstFrame(Image.ofUrl("https://example.com/first-frame.png"));
request.setDuration(5);
```

只设置 `firstFrame` 时，Wan 模型映射为 `input.img_url`；HappyHorse 模型映射为 `input.media[{type: "first_frame"}]`。

## 首尾帧生视频

```java
request.setModel(AliyunVideoModels.WAN_2_1_KF2V_PLUS);
request.setPrompt("纸飞机从城市街道飞向云端");
request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
request.setLastFrame(Image.ofUrl("https://example.com/last.png"));
```

同时设置首帧和尾帧时，适配器使用：

```text
input.first_frame_url
input.last_frame_url
```

## 参考图和视频输入

```java
request.setModel(AliyunVideoModels.WAN_2_6_R2V);
request.addReferenceImage(Image.ofUrl("https://example.com/character.png"));
request.addReferenceImage(Image.ofUrl("https://example.com/style.png"));
```

Wan 模型映射到 `input.reference_urls`，HappyHorse 模型映射到 `input.media[]` 中的 `reference_image`。

视频编辑输入：

```java
import com.agentsflex.core.model.video.Video;

request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));
```

Wan 模型映射到 `input.video_url`，HappyHorse 视频编辑模型映射到 `input.media[]` 中的 `video`。必须选择支持视频输入的模型。

音频输入：

```java
request.setAudioUrl("https://example.com/audio.wav");
```

音频会映射到 `input.audio_url`。必须选择支持音频输入的模型。

## 请求字段映射

### `input`

| 统一字段 | 阿里云字段 |
| --- | --- |
| `prompt` | `input.prompt` |
| `negativePrompt` | `input.negative_prompt` |
| 单张 `firstFrame` | `input.img_url` |
| 首尾帧 `firstFrame` | `input.first_frame_url` |
| `lastFrame` | `input.last_frame_url` |
| `referenceImages` | `input.reference_urls` |
| `sourceVideo.url` | `input.video_url` |
| `audioUrl` | `input.audio_url` |

### `parameters`

| 统一字段 | 阿里云字段 |
| --- | --- |
| `width` + `height` | `parameters.size`，格式为 `1280*720` |
| `resolution` | `parameters.resolution` |
| `aspectRatio` | `parameters.ratio` |
| `duration` | `parameters.duration` |
| `fps` | `parameters.fps` |
| `seed` | `parameters.seed` |
| `watermark` | `parameters.watermark` |
| `promptExtend` | `parameters.prompt_extend` |
| `generateAudio` | `parameters.generate_audio` |

## 扩展参数

阿里云不同模型的字段并不完全一致。适配器允许分别扩展 `input`、`parameters` 和请求顶层。

### 扩展 `input`

```java
Map<String, Object> input = new HashMap<>();
input.put("template", "cinematic");
request.addOption("input", input);
```

### 扩展 `parameters`

```java
Map<String, Object> parameters = new HashMap<>();
parameters.put("shot_type", "single");
request.addOption("parameters", parameters);
```

### 扩展请求顶层

```java
Map<String, Object> topLevel = new HashMap<>();
topLevel.put("workspace", "your-workspace-id");
request.addOption("topLevel", topLevel);
```

扩展参数在统一字段之后合并，因此同名扩展字段可以覆盖默认映射值。使用覆盖时应确保符合目标模型协议。

## 异步任务

创建任务时，适配器自动设置：

```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
X-DashScope-Async: enable
```

阿里云任务状态映射：

| 阿里云状态 | Agents-Flex 状态 |
| --- | --- |
| `PENDING` / `QUEUED` | `QUEUED` |
| `RUNNING` / `PROCESSING` | `RUNNING` |
| `SUCCEEDED` / `SUCCESS` | `SUCCEEDED` |
| `FAILED` | `FAILED` |
| `CANCELED` / `CANCELLED` | `CANCELED` |

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

模块包含默认跳过的真实集成测试。仅当设置 `DASHSCOPE_API_KEY` 时才会调用云端并产生费用：

```bash
export DASHSCOPE_API_KEY="your-api-key"

mvn -pl agents-flex-video/agents-flex-video-aliyun \
    -am \
    -Dtest=AliyunWanVideoModelIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
```

成功后视频保存到：

```text
agents-flex-video/agents-flex-video-aliyun/target/aliyun-generated.mp4
```

没有环境变量时测试自动跳过，不会创建云端任务。

## 注意事项

- API Key 不应写入源码、配置仓库或测试报告
- 不同模型支持的时长、尺寸、图片数量和参数组合不同
- 生成视频可能包含音频轨道，具体取决于模型行为和参数
- 返回的视频 URL 通常为临时签名地址，应及时下载
- `TIMED_OUT` 只表示本地等待超时，不代表阿里云任务失败
- 视频生成会产生云服务费用，自动化测试应显式设置环境变量后再运行

</div>
