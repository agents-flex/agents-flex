<div v-pre>

# TTS 和 STT 开发文档

## 概述

Agents-Flex 音频模块是一个统一的语音处理框架，提供 **语音识别（STT）** 和 **语音合成（TTS）** 两大核心能力。该模块采用接口驱动的设计哲学，屏蔽了不同云服务提供商的实现差异，为开发者提供一致、简洁的 API 体验。


与此同时，在大语言模型（LLM）对话场景中，模型会逐字或逐段输出响应文本。如果使用传统的同步 TTS，需要等待模型生成完整回复后才能开始合成语音，导致用户等待时间过长。

因此，Agents-Flex 同时支持 **流式TTS** 模式，将音频数据流式返回，实现大模型 LLM 边输出文字内容，边播放音频的能力。

> 但是，前端并没有太好的 **流式音频** 播放器，因此 Agents-Flex 开源了一个轻量的高性能、低延迟的 AI 音频流播放器： **AudioStreamPlayer**，专为 LLM 语音交互、实时 TTS 流式播放场景设计，文档 [AudioStreamPlayer](./audio-stream-player)。

- TTS：Text to Speech 文字转语音
- STT：Speech to Text 语音转文字

目前 Agents-Flex  已支持 **阿里云**、**腾讯云**、**火山引擎** 三大云服务提供商。

### 核心价值

- **统一抽象**：一套接口支持阿里云、腾讯云、火山引擎等多个云服务提供商，同时还可以自由扩展支持更多的服务提供商。
- **灵活切换**：无需修改业务代码即可更换底层服务提供商
- **流式优先**：原生支持流式处理，完美适配大模型实时交互场景
- **易于扩展**：清晰的接口设计，便于集成新的语音服务提供商

### 技术特点

| 特性 | 说明 |
|------|------|
| 接口标准化 | 定义统一的 `SpeechToTextModel` 和 `TextToSpeechModel` 接口 |
| 请求/响应模型 | 采用清晰的 Request/Response 对象封装输入输出 |
| 配置分离 | 通过 Options 对象管理语音参数，与业务逻辑解耦 |
| 流式支持 | 独立的 Streaming 接口，支持实时音频流处理 |
| 元数据扩展 | 基于 Metadata 基类，支持自定义扩展属性 |


## 架构设计

### 核心接口设计

#### STT 核心接口

```
SpeechToTextModel (接口)
    └── stt(SpeechToTextRequest): SpeechToTextResponse
```


**设计要点**：

- **单一职责**：仅负责将音频转换为文本
- **同步调用**：一次请求返回完整识别结果
- **多源支持**：支持文件、URL、数据流三种音频输入方式
- **自动格式检测**：内置 `AudioFormatUtil` 智能识别音频格式

**关键类**：

| 类名 | 职责 |
|------|------|
| `SpeechToTextModel` | STT 模型接口，定义 `stt()` 方法 |
| `SpeechToTextRequest` | 封装音频源和配置选项 |
| `SpeechToTextResponse` | 封装识别结果和元数据 |
| `SpeechToTextOptions` | 配置音频格式、采样率等参数 |
| `AudioFormatUtil` | 工具类，自动检测音频格式 |


#### TTS 核心接口

**同步 TTS**：

```
TextToSpeechModel (接口)
    └── tts(TextToSpeechRequest): TextToSpeechResponse
```


**流式 TTS**：

```
StreamingTextToSpeechModel (接口)
    ├── init(TextToSpeechOptions)
    ├── sendText(String)
    ├── addListener(StreamingTextToSpeechListener)
    └── close()
```


**设计要点**：

- **双接口设计**：同步接口适用于一次性转换，流式接口适用于实时场景
- **监听器模式**：通过 `StreamingTextToSpeechListener` 异步接收音频数据
- **资源管理**：实现 `Closeable` 接口，确保连接正确关闭
- **多监听器支持**：可同时注册多个监听器，实现并行处理

**关键类**：

| 类名 | 职责 |
|------|------|
| `TextToSpeechModel` | 同步 TTS 模型接口 |
| `StreamingTextToSpeechModel` | 流式 TTS 模型接口 |
| `TextToSpeechRequest` | 封装待合成文本和配置选项 |
| `TextToSpeechResponse` | 封装音频数据和元数据 |
| `TextToSpeechOptions` | 配置发音人、语速、音量、格式等参数 |
| `StreamingTextToSpeechListener` | 流式事件监听器接口 |




### 设计模式

#### 1. 策略模式（Strategy Pattern）

不同的云服务提供商实现相同的接口，运行时可灵活切换：

```java
// 阿里云实现
SpeechToTextModel stt = new AliyunSpeechToTextModel(aliyunConfig);

// 腾讯云实现
SpeechToTextModel stt = new TencentSpeechToTextModel(tencentConfig);

// 火山引擎实现
SpeechToTextModel stt = new VolcSpeechToTextModel(volcConfig);

// 业务代码无需修改
SpeechToTextResponse response = stt.stt(request);
```


#### 2. 观察者模式（Observer Pattern）

流式 TTS 通过监听器机制实现事件驱动：

```java
streamingTts.addListener(new StreamingTextToSpeechListener() {
    void onStart() { ... }
    void onReceived(byte[] bytes) { ... }
    void onError(Throwable e) { ... }
    void onComplete() { ... }
});
```



## 功能能力

### 语音识别（STT）

#### 核心能力

| 能力 | 说明 |
|------|------|
| 多格式支持 | MP3、WAV、PCM、FLAC、OGG、AAC、M4A |
| 多源输入 | 本地文件、网络 URL、内存数据流 |
| 自动格式检测 | 基于文件扩展名和文件头智能识别 |
| 高采样率支持 | 8kHz、16kHz、24kHz、48kHz |
| 批量处理 | 支持循环处理多个音频文件 |
| 元数据扩展 | 可附加自定义属性到响应中 |

#### 工作流程

```
音频源 → SpeechToTextRequest → SpeechToTextModel.stt()
       → SpeechToTextResponse → 识别文本
```


#### 技术亮点

- **厂商屏蔽**：自动将音频转换为不同厂商要去的数据格式，base64 或者 二进制数据等
- **格式推断**：`guessAudioFormat()` 方法自动检测音频格式
- **字节流处理**：`getAudioBytes()` 统一处理不同来源的音频数据
- **错误处理**：响应中包含成功标志和错误消息

---

### 语音合成（TTS）

#### 核心能力

| 能力 | 说明 |
|------|------|
| 多种输出格式 | MP3、WAV、PCM、OGG |
| 发音人选择 | 支持多种音色（男声、女声、童声等） |
| 参数调节 | 语速、音量、采样率可调 |
| 情感合成 | 部分提供商支持情感表达（开心、悲伤等） |
| 声音复刻 | 火山引擎支持自定义克隆音色 |
| 豆包大模型 | 支持豆包语音合成大模型 2.0 |

#### 工作流程（同步）

```
文本 + 配置 → TextToSpeechRequest → TextToSpeechModel.tts()
            → TextToSpeechResponse → 音频文件
```


#### 技术亮点

- **分块传输**：响应中包含多个音频块，支持增量处理
- **文件写入**：`writeTo()` 方法直接将音频保存到文件或输出流
- **默认值处理**：Options 提供 `getOrDefault()` 方法，简化配置
- **元数据携带**：可在响应中附加额外信息


### 流式处理能力

#### 什么是流式 TTS？

流式 TTS（Streaming Text-to-Speech）是一种**边生成边传输**的语音合成方式。与传统同步 TTS 等待完整音频不同，流式 TTS 在生成第一个音频块后立即开始传输，显著降低延迟。

#### 核心优势

| 对比项 | 同步 TTS | 流式 TTS |
|--------|----------|----------|
| 首字延迟 | 高（需等待完整音频） | 低（立即开始传输） |
| 内存占用 | 高（需存储完整音频） | 低（逐块处理） |
| 适用场景 | 短文本、离线场景 | 长文本、实时交互 |
| 用户体验 | 有等待感 | 流畅自然 |

#### 与大模型集成的价值

**场景描述**：

在大语言模型（LLM）对话场景中，模型会逐字或逐段输出响应文本。如果使用同步 TTS，需要等待模型生成完整回复后才能开始合成语音，导致用户等待时间过长。

**流式 TTS 解决方案**：

```
LLM 输出: "你好"     → TTS 立即合成 → 播放 "你好"
LLM 输出: "，欢迎"   → TTS 继续合成 → 播放 "，欢迎"
LLM 输出: "使用"     → TTS 继续合成 → 播放 "使用"
LLM 输出: "AI助手"   → TTS 继续合成 → 播放 "AI助手"
```


**效果**：

- ✅ **几乎零延迟**：用户听到语音的时间与看到文字几乎同步
- ✅ **自然流畅**：语音输出节奏与文字生成节奏一致
- ✅ **资源高效**：无需缓存完整音频，节省内存

#### 流式接口设计

**初始化阶段**：

```java
streamingTts.init(options);  // 建立 WebSocket 连接
```


**发送文本**：

```java
streamingTts.sendText("第一段文本");  // 可多次调用
streamingTts.sendText("第二段文本");
```


**接收音频**：

```java
listener.onReceived(byte[] bytes);  // 实时接收音频块
```


**关闭连接**：

```java
streamingTts.close();  // 释放资源
```


## 使用场景

### 智能助手场景

#### 场景描述

语音助手、聊天机器人等需要实时响应用户输入的应用。

#### 典型流程

```
用户语音提问 → STT 识别 → LLM 生成回复 → 流式 TTS 朗读 → 用户听到答案
```


#### 技术要点

- **STT**：将用户语音转换为文本
- **LLM**：生成智能回复（流式输出）
- **流式 TTS**：边生成边朗读，降低延迟

#### 优势

- 用户体验接近真人对话
- 响应延迟控制在秒级以内
- 支持打断和插话

---

### 内容创作场景

#### 场景 1：有声书生成

**需求**：将小说、文章转换为有声读物。

**技术方案**：

- 按段落分割文本（避免单次合成过长）
- 批量调用同步 TTS
- 合并音频文件（使用 FFmpeg）

**优势**：

- 自动化生产，节省人力成本
- 可选择不同发音人演绎不同角色
- 支持批量处理大量内容

---

#### 场景 2：视频配音

**需求**：为短视频、教程添加旁白。

**技术方案**：

- 脚本分段合成
- 调整语速匹配视频节奏
- 导出为标准音频格式

**优势**：

- 快速迭代，修改文案后重新生成立即可用
- 多语言支持，轻松制作国际化版本
- 成本低，无需聘请专业配音员

---

### 客户服务场景

#### 场景 1：语音通知系统

**需求**：向用户发送快递到达、账单提醒等语音通知。

**技术方案**：

- 模板化消息内容
- 批量调用 TTS 生成音频
- 集成电话系统自动拨打

**优势**：

- 触达率高，语音比短信更易被注意
- 个性化称呼，提升用户体验
- 可追溯，录音可作为服务凭证

---

#### 场景 2：IVR 语音导航

**需求**：客服热线的自动语音应答系统。

**技术方案**：

- 预生成常用菜单语音
- 动态合成个性化提示
- 按键识别跳转

**优势**：

- 7×24 小时服务，无需人工值守
- 快速更新话术，无需重新录制
- 多语言支持，服务国际客户

---

### 教育培训场景

#### 场景 1：在线课程配音

**需求**：为课件、PPT 添加讲解音频。

**技术方案**：

- 导入课程脚本
- 选择合适的发音人（如亲切的女声）
- 生成章节音频

**优势**：

- 教师无需反复录制
- 音质稳定，无口误
- 易于更新和维护

---

#### 场景 2：语言学习工具

**需求**：提供标准发音示范，帮助学习者练习口语。

**技术方案**：

- STT 评估学员发音准确度
- TTS 提供标准发音参考
- 对比分析，给出改进建议

**优势**：

- 随时随地练习，无需老师在场
- 即时反馈，加速学习进程
- 多口音支持，适应不同学习目标

---

### 会议转录场景

#### 场景描述

将会议录音转换为文字纪要。

#### 技术方案

- 批量上传录音文件
- 调用 STT 进行识别
- 合并识别结果，生成会议纪要

#### 优势

- 节省人工记录时间
-  searchable，便于后续检索
- 支持多语言会议


## 云服务提供商

### 阿里云（Aliyun）

**特点**：

- 成熟的 NLS（Natural Language Service）体系
- 丰富的发音人选择
- 支持流式 TTS 和 STT
- 自动 Token 管理

**适用场景**：

- 企业级应用
- 需要高可用性保障
- 已有阿里云生态集成

**技术栈**：

- SDK：`nls-sdk-tts`、`nls-sdk-common`
- 认证：AccessKey + Token
- 协议：HTTP REST + WebSocket

---

### 腾讯云（Tencent）

**特点**：

- 简洁的三元组认证（AppId + SecretId + SecretKey）
- 支持情感合成（开心、悲伤、愤怒等）
- 一句话识别（Flash Recognizer）
- 稳定的服务质量

**适用场景**：

- 社交娱乐应用
- 需要情感表达的場景
- 已有腾讯云生态集成

**技术栈**：

- SDK：`tencentcloud-speech-sdk-java`
- 认证：Credential（AppId + SecretId + SecretKey）
- 协议：HTTP REST + WebSocket

---

### 火山引擎（Volcengine）

**特点**：

- 仅需 API Key，认证最简洁
- 支持豆包大模型 2.0
- 支持声音复刻（自定义音色）
- 高级过滤控制（Markdown、Emoji）

**适用场景**：

- 创新应用场景
- 需要个性化音色
- 追求最新 AI 技术

**技术栈**：

- SDK：无第三方依赖，基于 OkHttp 自实现
- 认证：API Key
- 协议：HTTP REST + 自定义 WebSocket 二进制协议

**豆包大模型支持**：

| 模型 | 说明 |
|------|------|
| seed-tts-2.0 | 豆包语音合成大模型 2.0，支持预置音色 |
| seed-icl-2.0 | 豆包声音复刻大模型 2.0，支持自定义克隆音色 |


## 扩展与定制

### 新增云服务提供商

**步骤**：

1. **创建配置类**：继承 `BaseXxxConfig`，添加特定配置项
2. **实现 STT 模型**：实现 `SpeechToTextModel` 接口
3. **实现 TTS 模型**：实现 `TextToSpeechModel` 接口
4. **实现流式 TTS**（可选）：实现 `StreamingTextToSpeechModel` 接口
5. **编写测试用例**：验证功能正确性

**示例结构**：

```
agents-flex-audio-xxx/
├── XxxSpeechToTextConfig.java
├── XxxSpeechToTextModel.java
├── XxxTextToSpeechConfig.java
├── XxxTextToSpeechModel.java
└── XxxStreamingTextToSpeechModel.java
```


---

### 自定义音频处理

**场景**：需要对音频进行预处理或后处理。

**方案**：

- **STT 前处理**：在 `SpeechToTextRequest` 中添加音频降噪、增益调整
- **TTS 后处理**：在 `TextToSpeechResponse` 中添加音频混音、特效

**扩展点**：

- `AudioFormatUtil`：添加新的格式检测逻辑
- `Metadata`：通过元数据传递自定义参数

---

### 性能优化

**STT 优化**：

- 复用模型实例（线程安全）
- 批量处理时适当间隔，避免限流
- 选择合适的音频格式（MP3 传输更快）

**TTS 优化**：

- 短文本使用同步 TTS
- 长文本使用流式 TTS
- 缓存常用语音片段

**通用优化**：

- 连接池管理
- 异步处理
- 结果缓存

## 总结

Agents-Flex 音频模块通过统一的接口抽象，为开发者提供了灵活、高效的语音处理能力。无论是简单的文本转语音，还是复杂的实时对话系统，都能找到合适的解决方案。

**核心优势**：

- ✅ **统一抽象**：一套接口支持多个云服务提供商
- ✅ **流式优先**：原生支持流式处理，完美适配大模型场景
- ✅ **易于扩展**：清晰的架构设计，便于集成新提供商
- ✅ **生产就绪**：完善的错误处理和资源管理机制

**未来展望**：

- 支持更多云服务提供商（百度、科大讯飞等）
- 增强音频处理能力（降噪、混音、特效）
- 离线语音支持（本地模型部署）


</div>
