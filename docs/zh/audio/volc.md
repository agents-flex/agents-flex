<div v-pre>

# 火山引擎 TTS 和 STT 开发者文档


## 概述

`agents-flex-audio-volcengine` 是 Agents-Flex 框架的火山引擎音频服务实现模块，基于火山引擎智能语音服务提供：

- **语音识别（STT）**：将音频文件转换为文本，使用大模型识别引擎
- **语音合成（TTS）**：将文本转换为自然流畅的语音，支持豆包语音大模型 2.0
- **流式语音合成**：实时生成并传输音频数据，支持双向 WebSocket 通信

### 特性

- ✅ **简洁的认证方式**：仅需 API Key
- ✅ **豆包大模型支持**：支持豆包语音合成大模型 2.0 和声音复刻大模型 2.0
- ✅ **多种音频格式支持**：MP3、WAV、PCM 等
- ✅ **灵活的输入方式**：支持文件、URL、Base64 数据
- ✅ **流式处理**：支持实时音频流合成
- ✅ **丰富的发音人**：支持多种音色和自定义音色
- ✅ **参数可调**：语速、音量、采样率可自定义
- ✅ **高级过滤**：支持 Markdown、Emoji 过滤控制


## 模块架构

```
agents-flex-audio-volcengine/
├── src/main/java/com/agentsflex/audio/volc/
│   ├── BaseVolcConfig.java                # 基础配置类
│   ├── VolcSpeechToTextConfig.java        # STT 配置类
│   ├── VolcSpeechToTextModel.java         # STT 模型实现
│   ├── VolcTextToSpeechConfig.java        # TTS 配置类
│   ├── VolcTextToSpeechModel.java         # 同步 TTS 模型实现
│   └── VolcStreamingTextToSpeechModel.java # 流式 TTS 模型实现
├── src/test/java/com/agentsflex/audio/volc/
│   └── VolcAudioTest.java                 # 测试用例
└── pom.xml                                # Maven 配置
```


### 类关系图

```
BaseVolcConfig (基础配置)
    ├── VolcSpeechToTextConfig (STT 配置)
    │       └── VolcSpeechToTextModel (STT 实现)
    └── VolcTextToSpeechConfig (TTS 配置)
            ├── VolcTextToSpeechModel (同步 TTS)
            └── VolcStreamingTextToSpeechModel (流式 TTS)
                    └── VolcWebSocketClient (WebSocket 客户端)
                            └── protocol/* (协议层)
```



## 环境准备

### 获取火山引擎账号和 API Key

#### 步骤 1：注册火山引擎账号

访问 [火山引擎官网](https://www.volcengine.com/) 注册账号。

#### 步骤 2：开通智能语音服务

1. 登录火山引擎控制台
2. 搜索"语音技术"或"智能语音"
3. 开通语音识别和语音合成服务
4. 注意：可能需要申请使用豆包大模型权限

#### 步骤 3：创建 API Key

1. 进入火山引擎控制台
2. 找到"访问控制"或"API 密钥管理"
3. 创建 API Key
4. 记录 **API Key**

> ⚠️ **安全提示**：请妥善保管 API Key，不要提交到代码仓库！

#### 步骤 4：设置环境变量（推荐）

在 `~/.bashrc` 或 `~/.zshrc` 中添加：

```bash
export VOLC_API_KEY="your-api-key"
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

    <!-- 火山引擎音频模块 -->
    <dependency>
        <groupId>com.agents-flex</groupId>
        <artifactId>agents-flex-audio-volcengine</artifactId>
        <version>${agents-flex.version}</version>
    </dependency>
</dependencies>
```


#### 依赖说明

火山引擎模块仅依赖 `agents-flex-core`，无需额外的第三方 SDK，所有 HTTP 和 WebSocket 通信均通过 OkHttp 实现。


## 核心类说明

### 配置类

#### 1. BaseVolcConfig

**位置**：`com.agentsflex.audio.volc.BaseVolcConfig`

**作用**：火山引擎服务的基础配置，负责 API Key 管理。

**主要属性**：

| 属性 | 类型 | 说明 | 必填 |
|------|------|------|------|
| apiKey | String | 火山引擎 API Key | ✅ |

**使用示例**：

```java
BaseVolcConfig config = new BaseVolcConfig();
config.setApiKey(System.getenv("VOLC_API_KEY"));
```



#### 2. VolcSpeechToTextConfig

**位置**：`com.agentsflex.audio.volc.VolcSpeechToTextConfig`

**继承**：`BaseVolcConfig`

**作用**：语音识别服务的配置类。

**额外属性**：

| 属性 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| url | String | STT API 端点 | `https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash` |

**使用示例**：

```java
VolcSpeechToTextConfig config = new VolcSpeechToTextConfig();
config.setApiKey("your-api-key");

// 可选：自定义端点
config.setUrl("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash");
```



#### 3. VolcTextToSpeechConfig

**位置**：`com.agentsflex.audio.volc.VolcTextToSpeechConfig`

**继承**：`BaseVolcConfig`

**作用**：语音合成服务的配置类，支持选择豆包大模型版本。

**额外属性**：

| 属性 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| resourceId | String | 资源 ID（模型版本） | `seed-tts-2.0` |
| httpUrl | String | 同步 TTS API 端点 | `https://openspeech.bytedance.com/api/v3/tts/unidirectional` |
| webSocketUrl | String | 流式 TTS WebSocket 端点 | `wss://openspeech.bytedance.com/api/v3/tts/bidirection` |

**支持的 resourceId**：

| 值 | 说明 | 适用场景 |
|----|------|----------|
| `seed-tts-2.0` | 豆包语音合成大模型 2.0 | 使用豆包预置音色 |
| `seed-icl-2.0` | 豆包声音复刻大模型 2.0 | 使用声音复刻的自定义音色 |

**使用示例**：

```java
VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
config.setApiKey("your-api-key");

// 使用豆包语音合成大模型 2.0（默认）
config.setResourceId("seed-tts-2.0");

// 或使用声音复刻大模型 2.0
// config.setResourceId("seed-icl-2.0");
```



### STT 相关类

#### 4. VolcSpeechToTextModel

**位置**：`com.agentsflex.audio.volc.VolcSpeechToTextModel`

**实现接口**：`SpeechToTextModel`

**作用**：火山引擎语音识别模型实现，使用大模型识别引擎。

**构造方法**：

```java
public VolcSpeechToTextModel(VolcSpeechToTextConfig config)
```


**主要方法**：

```java
@Override
public SpeechToTextResponse stt(SpeechToTextRequest request)
```





### TTS 相关类

#### 5. VolcTextToSpeechModel

**位置**：`com.agentsflex.audio.volc.VolcTextToSpeechModel`

**实现接口**：`TextToSpeechModel`

**作用**：火山引擎同步语音合成模型实现。

**构造方法**：

```java
public VolcTextToSpeechModel(VolcTextToSpeechConfig config)
```


**主要方法**：

```java
@Override
public TextToSpeechResponse tts(TextToSpeechRequest request)
```




#### 6. VolcStreamingTextToSpeechModel

**位置**：`com.agentsflex.audio.volc.VolcStreamingTextToSpeechModel`

**实现接口**：`StreamingTextToSpeechModel`, `Closeable`

**作用**：火山引擎流式语音合成模型实现，使用双向 WebSocket 通信。

**构造方法**：

```java
public VolcStreamingTextToSpeechModel(VolcTextToSpeechConfig config)
```


**主要方法**：

```java
// 添加监听器
void addListener(StreamingTextToSpeechListener listener)

// 初始化（设置选项）
void init(TextToSpeechOptions options)

// 发送文本（可多次调用）
void sendText(String text)

// 关闭连接
void close() throws IOException
```

## 语音识别（STT）

### STT 快速开始

#### 最小化示例

```java
import com.agentsflex.audio.volc.*;
import com.agentsflex.core.audio.stt.*;
import java.io.File;

public class SimpleSTT {
    public static void main(String[] args) {
        // 1. 配置
        VolcSpeechToTextConfig config = new VolcSpeechToTextConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        // 2. 创建模型
        VolcSpeechToTextModel model = new VolcSpeechToTextModel(config);

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

#### 1. 使用 URL 作为音频源

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioUrl("https://example.com/audio.mp3");

SpeechToTextResponse response = model.stt(request);
```


#### 2. 使用 Base64 编码的音频数据

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioBase64(base64EncodedAudio);

SpeechToTextResponse response = model.stt(request);
```


> **注意**：`SpeechToTextRequest` 会自动将文件或流转换为 Base64。

#### 3. 自动检测音频格式

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioFile(new File("unknown_audio.mp3"));

// 自动检测格式
String format = request.guessAudioFormat();
System.out.println("检测到格式: " + format); // 输出: mp3

request.getOptions().setFormat(format);
```


#### 4. 处理识别结果

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
import com.agentsflex.audio.volc.*;
import com.agentsflex.core.audio.stt.*;
import java.io.*;
import java.util.*;

public class BatchSTTExample {

    public static void main(String[] args) {
        // 初始化配置
        VolcSpeechToTextConfig config = new VolcSpeechToTextConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        VolcSpeechToTextModel model = new VolcSpeechToTextModel(config);

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
        VolcSpeechToTextConfig config = new VolcSpeechToTextConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        VolcSpeechToTextModel model = new VolcSpeechToTextModel(config);

        // 读取会议录音
        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("meeting_recording.wav"));
        request.getOptions().setFormat("wav");

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
import com.agentsflex.audio.volc.*;
import com.agentsflex.core.audio.tts.*;
import java.io.File;

public class SimpleTTS {
    public static void main(String[] args) {
        // 1. 配置
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        // 2. 创建模型
        VolcTextToSpeechModel model = new VolcTextToSpeechModel(config);

        // 3. 配置选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("zh_female_vv_uranus_bigtts");  // 发音人
        options.setFormat("mp3");                         // 输出格式
        options.setSampleRate(16000);                     // 采样率

        // 4. 准备文本
        TextToSpeechRequest request = new TextToSpeechRequest(
            "你好，欢迎使用火山引擎语音合成服务！",
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
options.setVoice("zh_male_wanwan_bigtts");  // 男声

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

火山引擎支持多种发音人，以下是一些常用的发音人 ID：

| 发音人 ID | 性别 | 风格 | 适用场景 |
|----------|------|------|----------|
| zh_female_vv_uranus_bigtts | 女 | 标准女声 | 通用场景 |
| zh_male_wanwan_bigtts | 男 | 温暖男声 | 通用场景 |
| zh_female_shanshan_bigtts | 女 | 甜美女声 | 客服场景 |
| zh_male_qingfeng_bigtts | 男 | 清朗男声 | 新闻播报 |
| zh_female_yueyue_bigtts | 女 | 活泼女声 | 儿童内容 |

> **注意**：具体可用的发音人可能因火山引擎服务更新而变化，请以火山引擎官方文档为准。


#### 使用声音复刻音色

如果使用豆包声音复刻大模型 2.0，可以使用自己克隆的音色：

```java
VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
config.setApiKey(System.getenv("VOLC_API_KEY"));
config.setResourceId("seed-icl-2.0");  // 使用声音复刻大模型

VolcTextToSpeechModel model = new VolcTextToSpeechModel(config);

TextToSpeechOptions options = new TextToSpeechOptions();
options.setVoice("your_cloned_voice_id");  // 替换为你的音色 ID
options.setFormat("mp3");
options.setSampleRate(16000);

TextToSpeechRequest request = new TextToSpeechRequest(
    "这是使用复刻音色的语音合成测试。",
    options
);

TextToSpeechResponse response = model.tts(request);
response.writeTo(new File("cloned_voice.mp3"));
```



### 流式 TTS

#### 基础流式示例

```java
import com.agentsflex.audio.volc.*;
import com.agentsflex.core.audio.tts.*;
import java.io.FileOutputStream;

public class StreamingTTSExample {

    public static void main(String[] args) {
        // 1. 配置
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        // 2. 创建流式模型
        VolcStreamingTextToSpeechModel streamingTts =
            new VolcStreamingTextToSpeechModel(config);

        // 3. 配置选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("zh_female_vv_uranus_bigtts");
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

    private VolcStreamingTextToSpeechModel streamingTts;
    private volatile boolean isSpeaking = false;

    public void initialize() {
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        streamingTts = new VolcStreamingTextToSpeechModel(config);

        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("zh_female_vv_uranus_bigtts");
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



### TTS 完整示例

#### 示例：有声书生成器

```java
import com.agentsflex.audio.volc.*;
import com.agentsflex.core.audio.tts.*;
import java.io.*;
import java.util.*;

public class AudiobookGenerator {

    public static void main(String[] args) {
        // 初始化配置
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        VolcTextToSpeechModel model = new VolcTextToSpeechModel(config);

        // 配置语音参数
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("zh_female_shanshan_bigtts");  // 甜美女声
        options.setFormat("mp3");
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

    private VolcTextToSpeechModel ttsModel;
    private TextToSpeechOptions defaultOptions;

    public VoiceNotificationSystem() {
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        ttsModel = new VolcTextToSpeechModel(config);

        defaultOptions = new TextToSpeechOptions();
        defaultOptions.setVoice("zh_female_shanshan_bigtts");  // 客服女声
        defaultOptions.setFormat("mp3");
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

    public static void main(String[] args) {
        VoiceNotificationSystem notification = new VoiceNotificationSystem();

        notification.sendNotification(
            "13800138000",
            "您好，您的快递已到达小区门口，请及时领取。"
        );
    }
}
```



## 豆包大模型支持

### 豆包语音合成大模型 2.0（seed-tts-2.0）

**特点**：

- 支持多种预置音色
- 自然流畅的语音合成
- 支持情感表达
- 适用于通用场景

**使用方式**：

```java
VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
config.setResourceId("seed-tts-2.0");  // 默认值，可不设置
```



### 豆包声音复刻大模型 2.0（seed-icl-2.0）

**特点**：

- 支持声音复刻（克隆）
- 可以使用自定义音色
- 需要先在控制台创建音色
- 适用于个性化场景

**使用方式**：

```java
VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
config.setResourceId("seed-icl-2.0");  // 使用声音复刻大模型

TextToSpeechOptions options = new TextToSpeechOptions();
options.setVoice("your_cloned_voice_id");  // 使用克隆的音色
```


**如何创建克隆音色**：

1. 登录火山引擎控制台
2. 进入语音技术服务
3. 找到"声音复刻"或"音色库"
4. 上传音频样本（通常需要 10-30 秒的清晰录音）
5. 等待模型训练完成
6. 获取音色 ID



## 常见问题

### Q1: 如何获取火山引擎 API Key？

**A:**

1. 登录火山引擎控制台
2. 进入"访问控制"或"API 密钥管理"
3. 创建 API Key
4. 复制并保存 API Key


### Q2: STT 识别结果为空怎么办？

**可能原因和解决方案：**

1. **音频格式不正确**
   ```java
   // 明确指定格式
   request.getOptions().setFormat("wav");
   ```


2. **音频文件损坏**
   ```java
   // 检查文件是否有效
   System.out.println("文件大小: " + file.length() + " bytes");
   ```


3. **查看详细错误信息**
   ```java
   if (!response.isSuccess()) {
       System.err.println("错误: " + response.getMessage());
       System.err.println("元数据: " + response.getMetadataMap());
   }
   ```


4. **确认 API Key 有效且有权限**


### Q3: TTS 合成的音频有杂音？

**解决方案：**

1. **提高采样率**
   ```java
   options.setSampleRate(24000); // 或 48000
   ```


2. **使用 WAV 格式**
   ```java
   options.setFormat("wav");
   ```


3. **检查网络连接**（流式 TTS）



### Q4: 如何降低延迟？

**STT 优化：**

- 使用较小的音频文件
- 选择 MP3 格式（传输更快）
- 使用 16kHz 采样率

**TTS 优化：**

- 使用流式 TTS
- 选择较低的采样率（16kHz）
- 使用 MP3 格式


### Q5: 支持哪些音频格式？

**STT 支持：**

- MP3
- WAV
- PCM
- FLAC
- OGG
- AAC
- M4A

**TTS 输出：**

- MP3（推荐）
- WAV
- PCM
- OGG


### Q6: 如何使用豆包声音复刻功能？

**步骤**：

1. **开通声音复刻服务**
    - 登录火山引擎控制台
    - 开通语音技术服务
    - 申请使用声音复刻功能

2. **创建克隆音色**
    - 进入"声音复刻"或"音色库"
    - 上传音频样本（10-30 秒清晰录音）
    - 等待模型训练完成
    - 获取音色 ID

3. **使用克隆音色**
   ```java
   VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
   config.setApiKey(System.getenv("VOLC_API_KEY"));
   config.setResourceId("seed-icl-2.0");

   TextToSpeechOptions options = new TextToSpeechOptions();
   options.setVoice("your_cloned_voice_id");
   options.setFormat("mp3");
   options.setSampleRate(16000);

   VolcTextToSpeechModel model = new VolcTextToSpeechModel(config);
   TextToSpeechRequest request = new TextToSpeechRequest("测试文本", options);
   TextToSpeechResponse response = model.tts(request);
   ```


## 附录

### 相关资源

- [火山引擎语音识别官方文档](https://www.volcengine.com/docs/6561/1631584)
- [火山引擎语音合成官方文档](https://www.volcengine.com/docs/6561/2528925)
- [火山引擎流式语音合成文档](https://www.volcengine.com/docs/6561/2532486)
- [Agents-Flex 官方文档](https://agents-flex.com)


</div>
