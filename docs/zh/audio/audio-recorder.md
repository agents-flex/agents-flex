<div v-pre>

# 前端录音开发文档

## 1. 概述

在 AI 大模型应用中，语音交互已成为提升用户体验、降低使用门槛的重要方式。通过自然的语音输入，用户可以更便捷地与智能体（Agent）进行对话、下达指令或生成内容，从而构建出更具沉浸感和人性化的 AI 应用场景。

本文档旨在为前端开发者提供在浏览器环境中实现录音功能的最佳实践指南，重点介绍如何将录制的音频数据高效地接入 **STT（Speech-to-Text，语音转文字）** 服务，将其转换为文本后送入大模型进行处理，实现“语音进、文本出”的完整 AI 交互闭环。

> **注意**：Agents-Flex 核心库不再内置前端录音功能，而是推荐开发者根据项目需求选择成熟的第三方开源方案，以保持前端的轻量化和灵活性，同时专注于后端 AI 能力的标准化与稳定性。

## 2. 技术选型推荐

目前浏览器端录音方案成熟，以下列出几个经过社区验证的开源 JavaScript 库，开发者可根据项目具体需求（如兼容性、功能复杂度、包体积等）进行选择：

### 2.1 Recorder.js (xiangyuecn/Recorder)
- **GitHub**: https://github.com/xiangyuecn/Recorder
- **特点**：
    - 功能极其丰富，支持多种音频格式编码（mp3, wav, ogg, amr 等）。
    - 兼容性好，支持包括 IE 在内的多种浏览器环境（通过 Flash 或 WebRTC 降级）。
    - 提供实时波形绘制、音频处理等高级功能。
    - 文档完善，中文支持友好。
- **适用场景**：对音频格式有特定要求、需要高度定制化或需兼容老旧浏览器的项目。

### 2.2 Realtime Audio SDK (realtime-ai/realtime-audio-sdk)
- **GitHub**: https://github.com/realtime-ai/realtime-audio-sdk
- **特点**：
    - 专注于实时音频流处理。
    - 通常与特定的 AI 实时对话服务深度集成。
    - 优化了低延迟传输。
- **适用场景**：需要与实时 AI 对话引擎（如 Realtime API）紧密配合的场景。

### 2.3 Recorder (2fps/recorder)
- **GitHub**: https://github.com/2fps/recorder
- **特点**：
    - 轻量级，基于 Web Audio API 和 MediaRecorder API。
    - 使用简单，API 直观。
    - 专注于现代浏览器环境。
- **适用场景**：现代 Web 应用，追求快速集成和轻量级依赖的项目。

## 3. 录音流程设计

在 AI 应用中，一个典型的前端录音并转文字的流程如下：

1. **权限获取**：请求用户麦克风权限 (`navigator.mediaDevices.getUserMedia`)。
2. **开始录音**：初始化录音实例，开始捕获音频流。
3. **音频处理**（可选）：对音频数据进行降噪、增益等预处理，以提高 STT 识别准确率。
4. **停止录音**：结束录音，获取最终的音频 Blob 或 ArrayBuffer。
5. **发送 STT 请求**：将音频数据发送至后端 STT 服务或直接调用前端 STT SDK。
6. **接入大模型**：将 STT 转换后的文本作为 Prompt 的一部分，发送给 LLM（大语言模型）进行处理。
7. **结果展示**：接收 LLM 返回的结果并展示给用户。

### 3.1 代码示例（以 2fps/recorder 为例）

```javascript
import Recorder from 'recorder';

// 1. 创建录音实例
const recorder = new Recorder({
  sampleBits: 16, // 采样位数
  sampleRate: 16000, // 采样率，STT 通常推荐 16k
  numChannels: 1, // 声道数
});

// 2. 开始录音
async function startRecording() {
  try {
    await recorder.start();
    console.log('录音开始');
  } catch (error) {
    console.error('无法访问麦克风', error);
  }
}

// 3. 停止录音并获取数据
async function stopRecording() {
  const audioBlob = await recorder.stop();
  console.log('录音结束，获得 Blob:', audioBlob);

  // 4. 发送到 STT 服务
  sendToSttService(audioBlob);
}

function sendToSttService(blob) {
  const formData = new FormData();
  formData.append('audio', blob, 'recording.wav');

  fetch('/api/stt/transcribe', {
    method: 'POST',
    body: formData
  })
  .then(response => response.json())
  .then(data => {
    console.log('识别结果:', data.text);

    // 5. 将识别出的文本发送给大模型
    sendToLLM(data.text);
  })
  .catch(error => {
    console.error('STT 请求失败', error);
  });
}

function sendToLLM(text) {
  // 此处调用你的 LLM 接口
  console.log('正在向大模型发送提问:', text);
}
```

## 4. STT（语音转文字）集成

录音的最终目的是获取文本内容，以便与大模型进行交互。Agents-Flex 提供了完善的 STT 服务端能力，支持多种主流厂商的语音识别接口。

- **STT 官方文档**: [https://agentsflex.com/zh/audio/tts-stt.html](https://agentsflex.com/zh/audio/tts-stt.html)
- **建议**：
    - 对于短音频，可直接上传完整文件进行识别。
    - 对于长音频或实时性要求高的场景，建议采用**流式 STT**，即在录音过程中分片发送音频数据，实时获取部分识别结果，从而实现“边说边显”的流畅体验。

## 5. 相关功能：前端流式音频播放

在很多 AI 应用场景下，需要在前端播放由大模型生成的流式音频数据（TTS 输出）。请参考专门文档：

- **[AudioStreamPlayer 开发文档](./audio-stream-player.md)**


</div>
