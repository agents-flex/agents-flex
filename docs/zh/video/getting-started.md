<div v-pre>

# 视频生成快速开始

## 5 分钟快速上手

本指南使用阿里云百炼完成一个文生视频任务。切换到火山引擎时，只需要替换依赖、Config 和模型实现，核心请求与响应 API 保持不变。

## 前置要求

- Java 8 或更高版本
- Maven 3.6+
- 已开通阿里云百炼或火山引擎方舟的视频生成服务
- 对应服务商的 API Key

## 第一步：添加依赖

### 阿里云百炼

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 火山引擎方舟

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-video-volcengine</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

两个模块都会传递依赖 `agents-flex-core`，通常不需要再次显式引入。

## 第二步：配置 API Key

不要把 API Key 硬编码到源码。建议使用环境变量：

```bash
export DASHSCOPE_API_KEY="your-dashscope-api-key"
export ARK_API_KEY="your-ark-api-key"
```

阿里云配置：

```java
AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
```

火山引擎配置：

```java
VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();
config.setApiKey(System.getenv("ARK_API_KEY"));
```

## 第三步：创建文生视频请求

```java
import com.agentsflex.core.model.video.GenerateVideoRequest;

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt(
    "一架红色纸飞机飞过日出时的未来城市，电影感广角镜头，" +
    "镜头运动平滑，真实光照"
);
request.setNegativePrompt("模糊，抖动，畸变，低质量");
request.setSize(1280, 720);
request.setDuration(5);
request.setPromptExtend(true);
request.setWatermark(false);
```

不同模型支持的时长、尺寸和参数组合可能不同。模型不支持某个字段时，服务商可能忽略字段或返回参数错误。

## 第四步：提交并等待结果

```java
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.agentsflex.video.aliyun.AliyunWanVideoModel;
import com.agentsflex.video.aliyun.AliyunWanVideoModelConfig;

AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
config.setTimeoutMillis(10 * 60_000L);
config.setPollIntervalMillis(10_000L);

AliyunWanVideoModel videoModel = new AliyunWanVideoModel(config);
VideoResponse response = videoModel.generateAndWait(request);

if (response.getStatus() == VideoTaskStatus.SUCCEEDED) {
    System.out.println("视频生成成功: " + response.getVideo().getUrl());
} else {
    System.err.println(
        "视频生成失败: " + response.getErrorCode() + " - " +
        response.getErrorMessage()
    );
}
```

## 第五步：保存视频

```java
import java.io.File;

if (response.getVideo() != null) {
    File output = new File("output/generated-video.mp4");
    response.getVideo().writeToFile(output);
    System.out.println("视频已保存: " + output.getAbsolutePath());
}
```

视频下载地址通常是临时签名 URL，应在有效期内下载。

## 完整示例

```java
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.agentsflex.video.aliyun.AliyunWanVideoModel;
import com.agentsflex.video.aliyun.AliyunWanVideoModelConfig;

import java.io.File;

public class VideoQuickStart {
    public static void main(String[] args) {
        AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
        config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        config.setTimeoutMillis(10 * 60_000L);
        config.setPollIntervalMillis(10_000L);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setPrompt("一架红色纸飞机飞过日出时的未来城市");
        request.setNegativePrompt("模糊，抖动，畸变");
        request.setSize(1280, 720);
        request.setDuration(5);
        request.setPromptExtend(true);
        request.setWatermark(false);

        AliyunWanVideoModel videoModel = new AliyunWanVideoModel(config);
        VideoResponse response = videoModel.generateAndWait(request);

        if (response.getStatus() != VideoTaskStatus.SUCCEEDED) {
            throw new IllegalStateException(
                response.getErrorCode() + ": " + response.getErrorMessage()
            );
        }

        File output = new File("output/generated-video.mp4");
        response.getVideo().writeToFile(output);
        System.out.println(output.getAbsolutePath());
    }
}
```

## 非阻塞调用

Web 服务不建议长时间阻塞请求线程，可以分开提交和查询：

```java
VideoResponse submitted = videoModel.generate(request);
String taskId = submitted.getTaskId();

// 保存 taskId，稍后由定时任务、消息队列消费者或前端轮询查询
VideoResponse latest = videoModel.getResult(taskId);
```

推荐的服务端流程：

```text
客户端提交生成请求
    ↓
服务端调用 generate() 并保存 taskId
    ↓
后台任务定时调用 getResult(taskId)
    ↓
成功后下载或转存视频
    ↓
通知客户端结果
```

## 图生视频

```java
import com.agentsflex.core.model.image.Image;

GenerateVideoRequest request = new GenerateVideoRequest();
request.setPrompt("镜头缓慢向前推进，天空中的云自然移动");
request.setFirstFrame(Image.ofUrl("https://example.com/first-frame.png"));
request.setDuration(5);
request.setResolution("720p");
```

图片也可以使用内存字节：

```java
request.setFirstFrame(Image.ofBytes(imageBytes, "image/png"));
```

适配器会把图片转换成服务商支持的 URL 或 Data URI。具体模型可能只允许公网 URL。

## 首尾帧生视频

```java
request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
request.setLastFrame(Image.ofUrl("https://example.com/last.png"));
```

必须选择支持首尾帧的模型，例如阿里云 `wanx2.1-kf2v-plus`。

## 常见问题

### `TIMED_OUT` 是否表示云端任务失败？

不是。它只表示本地 `generateAndWait()` 达到了等待上限。可以继续使用响应中的任务 ID 调用 `getResult()`。

### 为什么任务成功但视频 URL 很快失效？

云服务商通常返回临时签名 URL。任务成功后应及时下载或转存。

### 为什么设置的参数没有生效？

不同模型支持的参数不同。先检查 Config 能力字段和模型官方文档，再确认字段应该放在统一请求还是服务商 `options` 中。

### 为什么生成耗时波动很大？

视频生成时间受到模型、分辨率、时长和服务商队列负载影响。生产环境应采用异步任务，而不是假定固定生成时间。

## 下一步

- [视频生成核心概念](./video-generation)
- [阿里云视频生成](./aliyun)
- [HappyHorse 视频生成](./happyhorse)
- [火山引擎视频生成](./volcengine)

</div>
