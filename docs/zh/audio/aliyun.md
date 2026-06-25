<div v-pre>


# 阿里云 TTS 和 STT 开发文档


## 概述

`agents-flex-audio-aliyun` 是 Agents-Flex 框架的阿里云音频服务实现模块，基于阿里云智能语音交互服务（NLS - Natural Language Service）提供：

- **语音识别（STT）**：将音频文件转换为文本
- **语音合成（TTS）**：将文本转换为自然流畅的语音
- **流式语音合成**：实时生成并传输音频数据

### 特性

- ✅ **自动 Token 管理**：内置 Token 生成和刷新机制
- ✅ **多种音频格式支持**：MP3、WAV、PCM 等
- ✅ **灵活的输入方式**：支持文件、URL、数据流
- ✅ **流式处理**：支持实时音频流合成
- ✅ **丰富的发音人**：多种音色可选
- ✅ **参数可调**：语速、音量、采样率可自定义


## 模块架构

```
agents-flex-audio-aliyun/
├── src/main/java/com/agentsflex/audio/aliyun/
│   ├── BaseAliyunConfig.java              # 基础配置类（Token 管理）
│   ├── AliyunSpeechToTextConfig.java      # STT 配置类
│   ├── AliyunSpeechToTextModel.java       # STT 模型实现
│   ├── AliyunTextToSpeechConfig.java      # TTS 配置类
│   ├── AliyunTextToSpeechModel.java       # 同步 TTS 模型实现
│   └── AliyunStreamingTextToSpeechModel.java # 流式 TTS 模型实现
├── src/test/java/com/agentsflex/audio/aliyun/
│   └── AliyunAudioTest.java               # 测试用例
└── pom.xml                                 # Maven 配置
```


### 类关系图

```
BaseAliyunConfig (基础配置)
    ├── AliyunSpeechToTextConfig (STT 配置)
    │       └── AliyunSpeechToTextModel (STT 实现)
    └── AliyunTextToSpeechConfig (TTS 配置)
            ├── AliyunTextToSpeechModel (同步 TTS)
            └── AliyunStreamingTextToSpeechModel (流式 TTS)
```



## 环境准备

### 获取阿里云账号和密钥

#### 步骤 1：注册阿里云账号

访问 [阿里云官网](https://www.aliyun.com/) 注册账号。

#### 步骤 2：开通智能语音交互服务

1. 登录阿里云控制台
2. 搜索"智能语音交互"或"NLS"
3. 开通服务（可能有免费额度）

#### 步骤 3：创建项目获取 AppKey

1. 进入智能语音交互控制台
2. 创建新项目
3. 记录 **AppKey**

#### 步骤 4：获取 AccessKey

1. 点击右上角头像 → "AccessKey 管理"
2. 创建 AccessKey（或使用已有的）
3. 记录 **AccessKey ID** 和 **AccessKey Secret**

> ⚠️ **安全提示**：请妥善保管密钥，不要提交到代码仓库！

#### 步骤 5：设置环境变量（推荐）

在 `~/.bashrc` 或 `~/.zshrc` 中添加：

```bash
export ALIYUN_ACCESS_KEY_ID="your-access-key-id"
export ALIYUN_ACCESS_KEY_SECRET="your-access-key-secret"
export ALIYUN_NLS_APP_KEY="your-app-key"
```


然后执行：

```bash
source ~/.zshrc  # 或 source ~/.bashrc
```



### Maven 依赖配置

#### 基础依赖

在项目的 `pom.xml` 中添加：

```xml
<dependencies>
    <!-- Agents-Flex Core -->
    <dependency>
        <groupId>com.agents-flex</groupId>
        <artifactId>agents-flex-core</artifactId>
        <version>${agents-flex.version}</version>
    </dependency>

    <!-- 阿里云音频模块 -->
    <dependency>
        <groupId>com.agents-flex</groupId>
        <artifactId>agents-flex-audio-aliyun</artifactId>
        <version>${agents-flex.version}</version>
    </dependency>
</dependencies>
```


#### 完整依赖树

阿里云模块会自动引入以下依赖：

```xml
<!-- 阿里云 NLS SDK（自动引入） -->
<dependency>
    <groupId>com.alibaba.nls</groupId>
    <artifactId>nls-sdk-tts</artifactId>
    <version>2.2.14</version>
</dependency>

<dependency>
    <groupId>com.alibaba.nls</groupId>
    <artifactId>nls-sdk-common</artifactId>
    <version>2.2.14</version>
</dependency>

<!-- JAXB Runtime（Java 11+ 需要） -->
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.8</version>
</dependency>
```


> **注意**：当前支持 jdk8+, 如果使用 Java 11 或更高版本，需要手动添加 `jaxb-runtime` 依赖。


## 核心类说明

### 配置类

#### 1. BaseAliyunConfig

**位置**：`com.agentsflex.audio.aliyun.BaseAliyunConfig`

**作用**：阿里云服务的基础配置，负责 Token 管理。

**主要属性**：

| 属性 | 类型 | 说明 | 必填 |
|------|------|------|------|
| accessKeyId | String | 阿里云 AccessKey ID | ✅ |
| accessKeySecret | String | 阿里云 AccessKey Secret | ✅ |
| appKey | String | NLS 项目 AppKey | ✅ |



**使用示例**：

```java
BaseAliyunConfig config = new BaseAliyunConfig();
config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));
```



#### 2. AliyunSpeechToTextConfig

**位置**：`com.agentsflex.audio.aliyun.AliyunSpeechToTextConfig`

**继承**：`BaseAliyunConfig`

**作用**：语音识别服务的配置类。

**额外属性**：

| 属性 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| endpoint | String | NLS 服务端点 | `https://nls-gateway-cn-shanghai.aliyuncs.com` |

**使用示例**：

```java
AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
config.setAccessKeyId("your-access-key-id");
config.setAccessKeySecret("your-access-key-secret");
config.setAppKey("your-app-key");

// 可选：自定义端点（通常不需要）
config.setEndpoint("https://nls-gateway-cn-shanghai.aliyuncs.com");
```



#### 3. AliyunTextToSpeechConfig

**位置**：`com.agentsflex.audio.aliyun.AliyunTextToSpeechConfig`

**继承**：`BaseAliyunConfig`

**作用**：语音合成服务的配置类。

**属性**：与 `BaseAliyunConfig` 相同，无额外属性。

**使用示例**：

```java
AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
config.setAccessKeyId("your-access-key-id");
config.setAccessKeySecret("your-access-key-secret");
config.setAppKey("your-app-key");
```



### STT 相关类

#### 4. AliyunSpeechToTextModel

**位置**：`com.agentsflex.audio.aliyun.AliyunSpeechToTextModel`

**实现接口**：`SpeechToTextModel`

**作用**：阿里云语音识别模型实现。

**构造方法**：

```java
public AliyunSpeechToTextModel(AliyunSpeechToTextConfig config)
```


**主要方法**：

```java
@Override
public SpeechToTextResponse stt(SpeechToTextRequest request)
```




### TTS 相关类

#### 5. AliyunTextToSpeechModel

**位置**：`com.agentsflex.audio.aliyun.AliyunTextToSpeechModel`

**实现接口**：`TextToSpeechModel`

**作用**：阿里云同步语音合成模型实现。

**构造方法**：

```java
public AliyunTextToSpeechModel(AliyunTextToSpeechConfig config)
```


**主要方法**：

```java
@Override
public TextToSpeechResponse tts(TextToSpeechRequest request)
```




#### 6. AliyunStreamingTextToSpeechModel

**位置**：`com.agentsflex.audio.aliyun.AliyunStreamingTextToSpeechModel`

**实现接口**：`StreamingTextToSpeechModel`, `Closeable`

**作用**：阿里云流式语音合成模型实现。

**构造方法**：

```java
public AliyunStreamingTextToSpeechModel(AliyunTextToSpeechConfig config)
```


**主要方法**：

```java
// 添加监听器
void addListener(StreamingTextToSpeechListener listener)

// 初始化（建立连接）
void init(TextToSpeechOptions options)

// 发送文本（可多次调用）
void sendText(String text)

// 关闭连接
void close() throws IOException
```



**特性**：

- ✅ 支持多监听器
- ✅ 支持心跳保持（发送空文本触发 ping）
- ✅ 异常隔离（单个监听器异常不影响其他监听器）


## 语音识别（STT）

### STT 快速开始

#### 最小化示例

```java
import com.agentsflex.audio.aliyun.*;
import com.agentsflex.core.audio.stt.*;
import java.io.File;

public class SimpleSTT {
    public static void main(String[] args) {
        // 1. 配置
        AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        // 2. 创建模型
        AliyunSpeechToTextModel model = new AliyunSpeechToTextModel(config);

        // 3. 准备请求
        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("test.mp3"));

        // 4. 执行识别
        SpeechToTextResponse response = model.stt(request);

        // 5. 输出结果
        System.out.println("识别结果: " + response.getResult());
    }
}
```



### STT 高级用法

#### 1. 指定音频格式和采样率

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioFile(new File("audio.wav"));

SpeechToTextOptions options = new SpeechToTextOptions();
options.setFormat("wav");
options.setSampleRate(16000);  // 16kHz 是最佳选择
request.setOptions(options);

SpeechToTextResponse response = model.stt(request);
```


#### 2. 使用 URL 作为音频源

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioUrl("https://example.com/audio.mp3");

SpeechToTextResponse response = model.stt(request);
```


#### 3. 使用音频流

```java
InputStream audioStream = new FileInputStream("audio.mp3");

SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioStream(audioStream);

SpeechToTextResponse response = model.stt(request);
```


#### 4. 自动检测音频格式

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioFile(new File("unknown_audio.mp3"));

// 自动检测格式
String format = request.guessAudioFormat();
System.out.println("检测到格式: " + format); // 输出: mp3

request.getOptions().setFormat(format);
```


#### 5. 处理识别结果

```java
SpeechToTextResponse response = model.stt(request);

if (response.isSuccess()) {
    String result = response.getResult();
    System.out.println("✓ 识别成功");
    System.out.println("文本: " + result);

    // 获取元数据
    System.out.println("元数据: " + response.getMetadataMap());
} else {
    System.err.println("✗ 识别失败");
    System.err.println("错误信息: " + response.getMessage());
}
```



### STT 完整示例

#### 示例：批量音频文件识别

```java
import com.agentsflex.audio.aliyun.*;
import com.agentsflex.core.audio.stt.*;
import java.io.*;
import java.util.*;

public class BatchSTTExample {

    public static void main(String[] args) {
        // 初始化配置
        AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        AliyunSpeechToTextModel model = new AliyunSpeechToTextModel(config);

        // 批量处理音频文件
        List<File> audioFiles = Arrays.asList(
            new File("meeting_part1.mp3"),
            new File("meeting_part2.mp3"),
            new File("meeting_part3.mp3")
        );

        StringBuilder fullTranscript = new StringBuilder();

        for (File file : audioFiles) {
            System.out.println("正在识别: " + file.getName());

            SpeechToTextRequest request = new SpeechToTextRequest();
            request.setAudioFile(file);
            request.getOptions().setFormat("mp3");
            request.getOptions().setSampleRate(16000);

            SpeechToTextResponse response = model.stt(request);

            if (response.isSuccess()) {
                String text = response.getResult();
                System.out.println("✓ " + file.getName() + ": " + text);
                fullTranscript.append(text).append("\n");
            } else {
                System.err.println("✗ 识别失败: " + response.getMessage());
            }
        }

        // 保存完整转录文本
        try (PrintWriter writer = new PrintWriter("transcript.txt", "UTF-8")) {
            writer.write(fullTranscript.toString());
            System.out.println("\n✓ 转录文本已保存到 transcript.txt");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```


#### 示例：会议录音转文字

```java
public class MeetingTranscription {

    public static void main(String[] args) {
        AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        AliyunSpeechToTextModel model = new AliyunSpeechToTextModel(config);

        // 读取会议录音
        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("meeting_recording.wav"));
        request.getOptions().setFormat("wav");
        request.getOptions().setSampleRate(16000);

        System.out.println("正在转录会议录音...");
        SpeechToTextResponse response = model.stt(request);

        if (response.isSuccess()) {
            String transcript = response.getResult();

            // 生成会议纪要
            System.out.println("\n========== 会议纪要 ==========");
            System.out.println(transcript);
            System.out.println("================================\n");

            // 保存到文件
            try (FileWriter writer = new FileWriter("meeting_minutes.txt")) {
                writer.write("会议时间: " + new Date() + "\n");
                writer.write("会议内容:\n\n");
                writer.write(transcript);
                System.out.println("✓ 会议纪要已保存");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
```



## 语音合成（TTS）

### 同步 TTS

#### 基础示例

```java
import com.agentsflex.audio.aliyun.*;
import com.agentsflex.core.audio.tts.*;
import java.io.File;

public class SimpleTTS {
    public static void main(String[] args) {
        // 1. 配置
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        // 2. 创建模型
        AliyunTextToSpeechModel model = new AliyunTextToSpeechModel(config);

        // 3. 配置选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("xiaoyun");      // 发音人
        options.setFormat("mp3");          // 输出格式
        options.setSpeed(0.0);             // 正常语速
        options.setVolume(50);             // 中等音量
        options.setSampleRate(16000);      // 采样率

        // 4. 准备文本
        TextToSpeechRequest request = new TextToSpeechRequest(
            "你好，欢迎使用阿里云语音合成服务！",
            options
        );

        // 5. 执行合成
        TextToSpeechResponse response = model.tts(request);

        // 6. 保存音频
        if (response.isSuccess()) {
            response.writeTo(new File("output.mp3"));
            System.out.println("✓ 音频已保存到 output.mp3");
        } else {
            System.err.println("✗ 合成失败: " + response.getMessage());
        }
    }
}
```



#### 调整语音参数

```java
TextToSpeechOptions options = new TextToSpeechOptions();

// 选择不同的发音人
options.setVoice("xiaogang");  // 男声

// 调整语速（范围：-500 ~ 500）
options.setSpeed(100.0);  // 加快语速

// 调整音量（范围：0 ~ 100）
options.setVolume(80);    // 增大音量

// 选择更高采样率（音质更好，文件更大）
options.setSampleRate(24000);

TextToSpeechRequest request = new TextToSpeechRequest(
    "这是一段测试音频，用于演示不同的语音参数。",
    options
);

TextToSpeechResponse response = model.tts(request);
response.writeTo(new File("custom_voice.mp3"));
```



#### 常用发音人列表

| 发音人 | 性别 | 风格 | 适用场景 |
|--------|------|------|----------|
| xiaoyun | 女 | 标准女声 | 通用场景 |
| xiaogang | 男 | 标准男声 | 通用场景 |
| ruoxi | 女 | 温柔女声 | 情感朗读 |
| siqi | 女 | 甜美童声 | 儿童内容 |
| sijia | 女 | 客服女声 | 客服场景 |
| aida | 女 | 新闻女声 | 新闻播报 |

> **注意**：具体可用的发音人可能因阿里云服务更新而变化，请以阿里云官方文档为准。


### 流式 TTS

#### 基础流式示例

```java
import com.agentsflex.audio.aliyun.*;
import com.agentsflex.core.audio.tts.*;
import java.io.FileOutputStream;

public class StreamingTTSExample {

    public static void main(String[] args) {
        // 1. 配置
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        // 2. 创建流式模型
        AliyunStreamingTextToSpeechModel streamingTts =
            new AliyunStreamingTextToSpeechModel(config);

        // 3. 配置选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("xiaoyun");
        options.setFormat("mp3");
        options.setSampleRate(16000);

        // 4. 准备输出文件
        try (FileOutputStream outputStream = new FileOutputStream("streaming.mp3")) {

            // 5. 添加监听器
            streamingTts.addListener(new StreamingTextToSpeechListener() {
                @Override
                public void onStart() {
                    System.out.println("✓ 开始合成...");
                }

                @Override
                public void onReceived(byte[] bytes) {
                    try {
                        outputStream.write(bytes);
                        System.out.print("."); // 进度指示
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    System.err.println("\n✗ 错误: " + throwable.getMessage());
                }

                @Override
                public void onComplete() {
                    System.out.println("\n✓ 合成完成！");
                }
            });

            // 6. 初始化
            streamingTts.init(options);

            // 7. 发送文本（可多次调用）
            System.out.println("正在流式合成...");
            streamingTts.sendText("这是流式语音合成测试。");
            streamingTts.sendText("可以分段发送文本，实现实时合成。");

            // 8. 关闭资源
            streamingTts.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```



#### 实时对话场景

```java
public class RealTimeAssistant {

    private AliyunStreamingTextToSpeechModel streamingTts;
    private volatile boolean isSpeaking = false;

    public void initialize() {
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        streamingTts = new AliyunStreamingTextToSpeechModel(config);

        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("xiaoyun");
        options.setFormat("pcm");  // PCM 格式适合实时播放
        options.setSampleRate(16000);

        streamingTts.init(options);

        streamingTts.addListener(new StreamingTextToSpeechListener() {
            @Override
            public void onStart() {
                isSpeaking = true;
                startAudioPlayer();
            }

            @Override
            public void onReceived(byte[] bytes) {
                playAudioChunk(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                isSpeaking = false;
                System.err.println("错误: " + throwable.getMessage());
            }

            @Override
            public void onComplete() {
                isSpeaking = false;
                System.out.println("播放完成");
            }
        });
    }

    public void speak(String text) {
        if (!isSpeaking) {
            streamingTts.sendText(text);
        }
    }

    private void startAudioPlayer() {
        // 启动音频播放器（使用 Java Sound API 或其他库）
    }

    private void playAudioChunk(byte[] bytes) {
        // 播放音频数据块
    }

    public void shutdown() {
        try {
            if (streamingTts != null) {
                streamingTts.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```



#### 多监听器模式

```java
// 可以同时添加多个监听器，实现不同的处理逻辑
streamingTts.addListener(new StreamingTextToSpeechListener() {
    @Override
    public void onReceived(byte[] bytes) {
        // 监听器 1：保存到文件
        fileOutputStream.write(bytes);
    }
    // 其他方法省略...
});

streamingTts.addListener(new StreamingTextToSpeechListener() {
    @Override
    public void onReceived(byte[] bytes) {
        // 监听器 2：发送到 WebSocket
        webSocketSession.sendBinary(bytes);
    }
    // 其他方法省略...
});

streamingTts.addListener(new StreamingTextToSpeechListener() {
    @Override
    public void onReceived(byte[] bytes) {
        // 监听器 3：实时播放
        audioPlayer.play(bytes);
    }
    // 其他方法省略...
});
```



#### 心跳保持

```java
// 长时间无文本发送时，发送空文本保持连接活跃
streamingTts.sendText("");  // 触发 ping

// 或者定期发送心跳
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    streamingTts.sendText("");
}, 0, 30, TimeUnit.SECONDS);
```



### TTS 完整示例

#### 示例：有声书生成器

```java
import com.agentsflex.audio.aliyun.*;
import com.agentsflex.core.audio.tts.*;
import java.io.*;
import java.util.*;

public class AudiobookGenerator {

    public static void main(String[] args) {
        // 初始化配置
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        AliyunTextToSpeechModel model = new AliyunTextToSpeechModel(config);

        // 配置语音参数
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("ruoxi");  // 温柔女声，适合朗读
        options.setFormat("mp3");
        options.setSpeed(-50.0);    // 稍慢的语速
        options.setVolume(60);
        options.setSampleRate(24000);

        // 读取文本文件
        String text = readFile("novel_chapter1.txt");

        // 按段落分割（避免单次合成文本过长）
        String[] paragraphs = text.split("\n\n");

        System.out.println("开始生成有声书...");
        System.out.println("共 " + paragraphs.length + " 个段落\n");

        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.isEmpty()) continue;

            System.out.println("合成第 " + (i + 1) + "/" + paragraphs.length + " 段");

            TextToSpeechRequest request = new TextToSpeechRequest(paragraph, options);
            TextToSpeechResponse response = model.tts(request);

            if (response.isSuccess()) {
                // 保存为单独的文件
                String filename = String.format("chapter1_%03d.mp3", i + 1);
                response.writeTo(new File(filename));
                System.out.println("✓ " + filename);
            } else {
                System.err.println("✗ 失败: " + response.getMessage());
            }

            // 短暂停顿，避免请求过于频繁
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.println("\n✓ 有声书生成完成！");
        System.out.println("提示：可以使用 FFmpeg 合并所有 MP3 文件");
    }

    private static String readFile(String path) {
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(path));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return content.toString();
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + path, e);
        }
    }
}
```


**合并音频（使用 FFmpeg）：**

```bash
# 创建文件列表
for f in chapter1_*.mp3; do echo "file '$f'" >> list.txt; done

# 合并所有音频
ffmpeg -f concat -safe 0 -i list.txt -c copy chapter1_complete.mp3
```



#### 示例：语音通知系统

```java
public class VoiceNotificationSystem {

    private AliyunTextToSpeechModel ttsModel;
    private TextToSpeechOptions defaultOptions;

    public VoiceNotificationSystem() {
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));

        ttsModel = new AliyunTextToSpeechModel(config);

        defaultOptions = new TextToSpeechOptions();
        defaultOptions.setVoice("sijia");  // 客服女声
        defaultOptions.setFormat("mp3");
        defaultOptions.setSpeed(0.0);
        defaultOptions.setVolume(70);
        defaultOptions.setSampleRate(16000);
    }

    /**
     * 发送语音通知
     */
    public void sendNotification(String phoneNumber, String message) {
        System.out.println("生成语音通知: " + message);

        TextToSpeechRequest request = new TextToSpeechRequest(message, defaultOptions);
        TextToSpeechResponse response = ttsModel.tts(request);

        if (response.isSuccess()) {
            // 保存临时音频文件
            File tempFile = new File("notification_" + System.currentTimeMillis() + ".mp3");
            response.writeTo(tempFile);

            // 调用电话服务发送语音（这里仅为示例）
            // phoneService.call(phoneNumber, tempFile);

            System.out.println("✓ 语音通知已生成: " + tempFile.getAbsolutePath());

            // 清理临时文件
            // tempFile.delete();
        } else {
            System.err.println("✗ 生成失败: " + response.getMessage());
        }
    }

    /**
     * 发送紧急通知（更快的语速）
     */
    public void sendUrgentNotification(String phoneNumber, String message) {
        TextToSpeechOptions urgentOptions = new TextToSpeechOptions();
        urgentOptions.setVoice("sijia");
        urgentOptions.setFormat("mp3");
        urgentOptions.setSpeed(100.0);  // 加快语速
        urgentOptions.setVolume(90);    // 增大音量
        urgentOptions.setSampleRate(16000);

        TextToSpeechRequest request = new TextToSpeechRequest(message, urgentOptions);
        TextToSpeechResponse response = ttsModel.tts(request);

        if (response.isSuccess()) {
            File tempFile = new File("urgent_" + System.currentTimeMillis() + ".mp3");
            response.writeTo(tempFile);
            System.out.println("✓ 紧急通知已生成");
        }
    }

    public static void main(String[] args) {
        VoiceNotificationSystem notification = new VoiceNotificationSystem();

        // 普通通知
        notification.sendNotification(
            "13800138000",
            "您好，您的快递已到达小区门口，请及时领取。"
        );

        // 紧急通知
        notification.sendUrgentNotification(
            "13800138000",
            "紧急通知：您所在区域即将停水，请提前储备生活用水。"
        );
    }
}
```



## Token 管理机制

### Token 的作用

阿里云 NLS 服务使用 Token 进行身份验证，Token 是通过 AccessKey ID 和 AccessKey Secret 生成的临时访问凭证。

### 自动 Token 生成

`BaseAliyunConfig` 类内置了 Token 生成逻辑：

```java
public String createToken() {
    AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
    try {
        accessToken.apply();  // 向阿里云服务器请求 Token
        return accessToken.getToken();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}
```


### Token 生命周期

- **有效期**：通常为 24 小时
- **自动刷新**：每次调用 `createToken()` 都会生成新 Token
- **建议**：生产环境中可以缓存 Token，避免频繁请求

### Token 缓存优化（可选）

```java
public class CachedAliyunConfig extends BaseAliyunConfig {

    private String cachedToken;
    private long tokenExpiryTime;

    @Override
    public String createToken() {
        long currentTime = System.currentTimeMillis();

        // 如果 Token 未过期，直接返回缓存的 Token
        if (cachedToken != null && currentTime < tokenExpiryTime) {
            return cachedToken;
        }

        // 生成新 Token
        String newToken = super.createToken();
        this.cachedToken = newToken;
        this.tokenExpiryTime = currentTime + 23 * 60 * 60 * 1000; // 23小时后过期

        return newToken;
    }
}
```





</div>
