<div v-pre>

# TTS 和 STT 快速开始

## 5 分钟快速上手

本指南将帮助您在 5 分钟内完成 Agents-Flex 音频模块的配置和使用。


## 前置要求

- Java 8 或更高版本
- Maven 3.6+
- 阿里云(或腾讯云、火山引擎等任意）账号（获取 API 密钥）


## 第一步：添加依赖

在您的 `pom.xml` 中添加以下依赖：

```xml
<!-- 核心模块 -->
<dependency>
    <groupId>com.agents-flex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>

<!-- 阿里云音频服务 -->
<dependency>
    <groupId>com.agents-flex</groupId>
    <artifactId>agents-flex-audio-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```


> **提示**：如果使用腾讯云，替换为 `agents-flex-audio-tencent`，使用火山引擎，替换为 `agents-flex-audio-volcengine`


## 第二步：配置 API 密钥


```java
import com.agentsflex.audio.aliyun.AliyunSpeechToTextConfig;
import com.agentsflex.audio.aliyun.AliyunTextToSpeechConfig;

// STT 配置
AliyunSpeechToTextConfig sttConfig = new AliyunSpeechToTextConfig();
sttConfig.setAppKey("your-app-key");
sttConfig.setAccessKeyId("your-access-key-id");
sttConfig.setAccessKeySecret("your-access-key-secret");

// TTS 配置
AliyunTextToSpeechConfig ttsConfig = new AliyunTextToSpeechConfig();
ttsConfig.setAppKey("your-app-key");
ttsConfig.setAccessKeyId("your-access-key-id");
ttsConfig.setAccessKeySecret("your-access-key-secret");
```




## 第三步：语音转文字（STT）

创建一个完整的 STT 示例：

```java
import com.agentsflex.core.audio.stt.*;
import com.agentsflex.audio.aliyun.AliyunSpeechToTextConfig;
import com.agentsflex.audio.aliyun.AliyunSpeechToTextModel;
import java.io.File;

public class QuickStartSTT {
    public static void main(String[] args) {
        // 1. 初始化配置
        AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
        config.setAppKey(System.getenv("ALIYUN_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));

        // 2. 创建 STT 模型
        SpeechToTextModel sttModel = new AliyunSpeechToTextModel(config);

        // 3. 准备音频文件
        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("test.mp3"));

        // 4. 配置选项（可选，会自动检测）
        SpeechToTextOptions options = new SpeechToTextOptions();
        options.setFormat("mp3");
        options.setSampleRate(16000);
        request.setOptions(options);

        // 5. 执行识别
        System.out.println("正在识别音频...");
        SpeechToTextResponse response = sttModel.stt(request);

        // 6. 输出结果
        if (response.isSuccess()) {
            System.out.println("✓ 识别成功！");
            System.out.println("文本内容: " + response.getResult());
        } else {
            System.err.println("✗ 识别失败: " + response.getMessage());
        }
    }
}
```


**运行结果：**

```
正在识别音频...
✓ 识别成功！
文本内容: 你好，欢迎使用 Agents-Flex 语音识别服务。
```



## 第四步：文字转语音（TTS）

创建一个完整的 TTS 示例：

```java
import com.agentsflex.core.audio.tts.*;
import com.agentsflex.audio.aliyun.AliyunTextToSpeechConfig;
import com.agentsflex.audio.aliyun.AliyunTextToSpeechModel;
import java.io.File;

public class QuickStartTTS {
    public static void main(String[] args) {
        // 1. 初始化配置
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAppKey(System.getenv("ALIYUN_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));

        // 2. 创建 TTS 模型
        AliyunTextToSpeechModel ttsModel = new AliyunTextToSpeechModel(config);

        // 3. 配置语音选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("xiaoyun");      // 发音人：小云
        options.setFormat("mp3");          // 输出格式
        options.setSpeed(0.0);             // 正常语速
        options.setVolume(50);             // 中等音量
        options.setSampleRate(16000);      // 采样率

        // 4. 准备文本
        TextToSpeechRequest request = new TextToSpeechRequest(
            "你好，欢迎使用 Agents-Flex 语音合成服务！",
            options
        );

        // 5. 执行合成
        System.out.println("正在合成语音...");
        TextToSpeechResponse response = ttsModel.tts(request);

        // 6. 保存音频文件
        if (response.isSuccess()) {
            File outputFile = new File("output.mp3");
            response.writeTo(outputFile);
            System.out.println("✓ 合成成功！");
            System.out.println("✓ 音频已保存到: " + outputFile.getAbsolutePath());
        } else {
            System.err.println("✗ 合成失败: " + response.getMessage());
        }
    }
}
```


**运行结果：**

```
正在合成语音...
✓ 合成成功！
✓ 音频已保存到: /path/to/output.mp3
```



## 第五步：流式 TTS（进阶）

实现实时语音合成：

```java
import com.agentsflex.core.audio.tts.*;
import com.agentsflex.audio.aliyun.AliyunTextToSpeechConfig;
import com.agentsflex.audio.aliyun.AliyunStreamingTextToSpeechModel;
import java.io.FileOutputStream;

public class QuickStartStreamingTTS {
    public static void main(String[] args) throws Exception {
        // 1. 初始化配置
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAppKey(System.getenv("ALIYUN_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));

        // 2. 创建流式 TTS 模型
        StreamingTextToSpeechModel streamingTts =
            new AliyunStreamingTextToSpeechModel(config);

        // 3. 配置选项
        TextToSpeechOptions options = new TextToSpeechOptions();
        options.setVoice("xiaoyun");
        options.setFormat("mp3");
        options.setSampleRate(16000);

        // 4. 准备输出文件
        FileOutputStream outputStream = new FileOutputStream("streaming.mp3");

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
                try {
                    outputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // 6. 初始化并发送文本
        streamingTts.init(options);
        System.out.println("正在流式合成...");
        streamingTts.sendText("这是流式语音合成测试。");
        streamingTts.sendText("可以实时接收音频数据。");

        // 7. 关闭资源
        streamingTts.close();
    }
}
```


**运行结果：**

```
正在流式合成...
✓ 开始合成...
.........................
✓ 合成完成！
```



## 完整示例项目

创建一个完整的项目结构：

```
my-audio-project/
├── pom.xml
├── .env
└── src/main/java/
    ├── QuickStartSTT.java
    ├── QuickStartTTS.java
    └── QuickStartStreamingTTS.java
```


### 完整的 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-audio-project</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <agents-flex.version>最新版本号</agents-flex.version>
    </properties>

    <dependencies>
        <!-- Agents-Flex Core -->
        <dependency>
            <groupId>com.agents-flex</groupId>
            <artifactId>agents-flex-core</artifactId>
            <version>${agents-flex.version}</version>
        </dependency>

        <!-- 阿里云音频 -->
        <dependency>
            <groupId>com.agents-flex</groupId>
            <artifactId>agents-flex-audio-aliyun</artifactId>
            <version>${agents-flex.version}</version>
        </dependency>

        <!-- SLF4J 日志 -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.9</version>
        </dependency>
    </dependencies>
</project>
```



## 常用场景速查

### 场景 1：会议录音转文字

```java
SpeechToTextRequest request = new SpeechToTextRequest();
request.setAudioFile(new File("meeting.mp3"));
request.getOptions().setSampleRate(16000);

SpeechToTextResponse response = sttModel.stt(request);
System.out.println("会议纪要: " + response.getResult());
```


### 场景 2：语音助手播报

```java
TextToSpeechOptions options = new TextToSpeechOptions();
options.setVoice("xiaoyun");
options.setSpeed(50.0); // 稍快

TextToSpeechRequest request = new TextToSpeechRequest(
    "今天的天气晴朗，温度适宜。",
    options
);

TextToSpeechResponse response = ttsModel.tts(request);
response.writeTo(new File("weather_report.mp3"));
```


### 场景 3：多语言支持

```java
// 中文
TextToSpeechOptions cnOptions = new TextToSpeechOptions();
cnOptions.setVoice("xiaoyun");

// 英文（如果支持）
TextToSpeechOptions enOptions = new TextToSpeechOptions();
enOptions.setVoice("xiaomei");
```


### 场景 4：批量处理

```java
List<File> audioFiles = Arrays.asList(
    new File("audio1.mp3"),
    new File("audio2.mp3"),
    new File("audio3.mp3")
);

for (File file : audioFiles) {
    SpeechToTextRequest request = new SpeechToTextRequest();
    request.setAudioFile(file);

    SpeechToTextResponse response = sttModel.stt(request);
    System.out.println(file.getName() + ": " + response.getResult());
}
```


## 故障排查

### 问题 1：认证失败

**错误信息：** `Authentication failed` 或 `Invalid credentials`

**解决方案：**
```bash
# 检查环境变量是否正确设置
echo $ALIYUN_APP_KEY
echo $ALIYUN_ACCESS_KEY_ID
echo $ALIYUN_ACCESS_KEY_SECRET

# 确认密钥没有多余空格
```


### 问题 2：找不到音频文件

**错误信息：** `FileNotFoundException`

**解决方案：**
```java
File audioFile = new File("test.mp3");
System.out.println("文件路径: " + audioFile.getAbsolutePath());
System.out.println("文件存在: " + audioFile.exists());
```


### 问题 3：识别结果为空

**可能原因：**
- 音频格式不正确
- 采样率不匹配
- 音频文件损坏

**解决方案：**
```java
// 自动检测格式
String format = request.guessAudioFormat();
System.out.println("检测到格式: " + format);

// 手动指定格式
request.getOptions().setFormat("wav");
request.getOptions().setSampleRate(16000);
```


### 问题 4：TTS 合成失败

**错误信息：** `Voice not found` 或 `Invalid parameters`

**解决方案：**
```java
// 使用默认发音人
TextToSpeechOptions options = new TextToSpeechOptions();
options.setVoice("xiaoyun"); // 确保使用有效的发音人
options.setFormat("mp3");
options.setSampleRate(16000);
```


</div>
