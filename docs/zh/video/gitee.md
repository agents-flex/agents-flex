# Gitee AI 视频生成

`agents-flex-video-gitee` 是 Agents-Flex 对 Gitee AI 模力方舟视频生成接口的适配模块。它支持文生视频、图生视频、参考图片与驱动视频生成，以及音频驱动视频，并统一转换为 Agents-Flex 的异步视频任务接口。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 创建模型

访问令牌应通过环境变量或密钥管理服务注入，不要写入源码。

```java
import com.agentsflex.video.gitee.GiteeVideoModel;
import com.agentsflex.video.gitee.GiteeVideoModelConfig;
import com.agentsflex.video.gitee.GiteeVideoModels;

GiteeVideoModelConfig config = new GiteeVideoModelConfig();
config.setApiKey(System.getenv("GITEE_AI_API_KEY"));
config.setModel(GiteeVideoModels.HAPPYHORSE_1_1);
config.setTimeoutMillis(10 * 60_000L);
config.setPollIntervalMillis(10_000L);

GiteeVideoModel videoModel = new GiteeVideoModel(config);
```

默认服务地址为 `https://ai.gitee.com/v1`，使用 Bearer Token 鉴权。

## 文生视频

没有输入图片、视频和音频时，模型调用：

```text
POST /async/videos/generations
```

```java
GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt("一架红色纸飞机穿过清晨的未来城市，电影感运镜");

VideoResponse response = videoModel.generateAndWait(request);
Video video = response.getVideo();
```

文档当前列出的文生视频模型包括 HappyHorse、Wan、Vidu 和 HunyuanVideo：

```java
GiteeVideoModels.HAPPYHORSE_1_1
GiteeVideoModels.HAPPYHORSE_1_0
GiteeVideoModels.WAN_2_7
GiteeVideoModels.WAN_2_1_T2V_14B
GiteeVideoModels.VIDU_Q3_TURBO
GiteeVideoModels.VIDU_Q3_PRO
GiteeVideoModels.VIDU_Q2_TURBO
GiteeVideoModels.VIDU_Q2_PRO
GiteeVideoModels.HUNYUAN_VIDEO_1_5
```

## 图生视频

设置 `firstFrame` 时调用 JSON 形式的图生视频接口：

```text
POST /async/videos/image-to-video
```

```java
GiteeVideoModelConfig config = new GiteeVideoModelConfig();
config.setApiKey(System.getenv("GITEE_AI_API_KEY"));
config.setModel(GiteeVideoModels.LTX_2);

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt("镜头缓慢向前推进，云层自然流动");
request.setFirstFrame(Image.ofUrl("https://example.com/first-frame.png"));

VideoResponse response = new GiteeVideoModel(config).generateAndWait(request);
```

`firstFrame` 可以是公网 URL 或 Base64/Data URI。当前文档列出的图生视频模型包括 `LTX-2`、`Wan2_2-I2V-A14B` 和 `InfiniteTalk`。

## 图片视频生成

同时设置参考图片和源视频时调用：

```text
POST /async/videos/image-video-to-video
Content-Type: multipart/form-data
```

该接口的官方协议要求上传二进制文件，因此参考图和驱动视频必须通过 `ofBytes` 提供。模块不会隐式下载远程大文件再上传。

```java
GenerateVideoRequest request = new GenerateVideoRequest();
request.setModel(GiteeVideoModels.HAPPYHORSE_1_0);
request.setFirstFrame(Image.ofBytes(imageBytes, "image/png"));
request.setSourceVideo(Video.ofBytes(videoBytes, "video/mp4"));
request.addOption("guidance", 1.5F);
request.addOption("steps", 25);

VideoResponse response = videoModel.generateAndWait(request);
```

也可以使用 `addReferenceImage` 代替 `firstFrame`，但该接口只接受一张参考图片。模型特有的 `lora`、`checkpoint`、`version`、`stylize`、`guidance`、`steps` 等字段通过 `addOption` 传入。

## 音频视频生成

同时设置 `audioUrl` 和带 URL 的 `sourceVideo` 时调用：

```text
POST /async/videos/audio-video-to-video
```

```java
GenerateVideoRequest request = new GenerateVideoRequest();
request.setModel(GiteeVideoModels.DUIX_HEYGEM);
request.setAudioUrl("https://example.com/speech.wav");
request.setSourceVideo(Video.ofUrl("https://example.com/person.mp4"));

VideoResponse response = videoModel.generateAndWait(request);
```

## 端点选择规则

| 请求素材 | 调用端点 | 请求格式 |
|---|---|---|
| 只有提示词 | `/async/videos/generations` | JSON |
| `firstFrame` | `/async/videos/image-to-video` | JSON |
| 参考图 + `sourceVideo` | `/async/videos/image-video-to-video` | multipart |
| `audioUrl` + `sourceVideo` | `/async/videos/audio-video-to-video` | JSON |

Gitee AI 当前没有首尾帧生成端点，因此设置 `lastFrame` 会返回参数错误。音频驱动模式不能同时携带参考图片。

## 异步任务

提交接口返回 `task_id`，模块通过下面的接口轮询任务：

```text
GET /task/{task_id}
```

状态映射如下：

| Gitee AI 状态 | `VideoTaskStatus` |
|---|---|
| `waiting` | `QUEUED` |
| `in_progress` | `RUNNING` |
| `success` | `SUCCEEDED` |
| `failure` | `FAILED` |
| `cancelled` | `CANCELED` |

任务结果中的下载链接只保留一天，生产环境应在任务成功后及时下载并转存。

## 在线测试

在线测试默认跳过。设置访问令牌后可以执行真实文生视频验证：

```bash
export GITEE_AI_API_KEY=your-token

mvn -pl agents-flex-video/agents-flex-video-gitee \
    -am \
    -Dtest=GiteeVideoModelIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
```

生成结果会下载到：

```text
agents-flex-video/agents-flex-video-gitee/target/gitee-generated.mp4
```

接口定义以 [模力方舟开放接口](https://ai.gitee.com/docs/openapi/v1#tag/%E8%A7%86%E9%A2%91%E7%94%9F%E6%88%90/post/async/videos/generations) 为准。
