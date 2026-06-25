<div v-pre>

# AudioStreamPlayer 开发文档


`AudioStreamPlayer` 是一个轻量的 **高性能、低延迟的 AI 音频流播放器**
专为 LLM 语音交互、实时 TTS 流式播放场景设计。基于浏览器原生 MSE (Media Source Extensions) 构建，实现边生成边播放，消除卡顿与静音间隙。


本文档旨在帮助开发者快速集成 `AudioStreamPlayer`，实现 AI 场景下（如 TTS 流式语音、实时对话）的低延迟、高性能音频播放。

> Git 开源地址： https://github.com/agents-flex/audioStreamPlayer

## ✨ 特点

在 AI 语音对话场景中，传统 `<audio>` 标签往往面临以下痛点：
*   **延迟高**：需要等待整个文件生成完毕才能播放。
*   **卡顿/断连多**：多个音频片段拼接时会出现明显的静音间隙。
*   **内存泄漏风险**：长时间运行可能导致浏览器内存堆积。

`AudioStreamPlayer` 通过 **流式缓冲机制** 和 **智能状态管理**，实现了：
1.  **极低首帧延迟**：只需缓冲少量数据（默认 300ms）即可开始播放。
2.  **无缝拼接**：内部使用环形缓冲区平滑处理音频数据流，消除片段间的停顿。
3.  **内存安全**：自动管理缓冲上限（默认 5s），防止长时间运行导致页面崩溃。
4.  **状态可控**：提供清晰的状态机（Buffering, Playing, Paused 等），方便 UI 同步。

## 📦 安装

通过 npm 或 yarn 安装：

```bash
npm install @agents-flex/audio-stream-player
```

或者使用 yarn：

```bash
yarn add @agents-flex/audio-stream-player
```


## 🚀 快速开始

### 1. 导入模块

支持 ES Module 和 CommonJS 引入：

```typescript
// ES Module (推荐，适用于 Vite/Webpack/Rollup)
import { AudioStreamPlayer } from '@agents-flex/audio-stream-player';

// CommonJS (适用于 Node.js 环境或旧版打包工具)
const { AudioStreamPlayer } = require('@agents-flex/audio-stream-player');
```

### 2. 基础使用示例

```typescript
// 1. 创建播放器实例
const player = new AudioStreamPlayer({
  autoplay: true,       // 缓冲足够后自动播放
  minBufferMs: 300,     // 最小缓冲 300ms 后启动
  onStateChange: (state) => {
    console.log('当前状态:', state);
    // 可在此更新 UI，如显示“正在思考...”、“正在说话”
  },
  onError: (err) => {
    console.error('播放出错:', err);
  }
});

// 2. 打开媒体源（必须步骤）
await player.open();

// 3. 模拟从 AI 服务接收音频流并播放
async function handleAudioStream() {
  const response = await fetch('/api/tts-stream');
  const reader = response.body!.getReader();

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    // 4. 喂入音频数据块 (Uint8Array)
    player.feed(value);
  }

  // 5. 通知播放器流已结束
  player.close();
}

handleAudioStream();
```

### 3. 完整交互示例（含暂停与销毁）

```typescript
let player: AudioStreamPlayer | null = null;

// --- 开始播放 ---
async function start() {
  player = new AudioStreamPlayer({
    autoplay: true,
    minBufferMs: 300,
    onStateChange: (state) => console.log(`State: ${state}`)
  });

  await player.open();

  // 模拟流式数据输入
  const res = await fetch("/audio-stream.mp3");
  const reader = res.body!.getReader();

  const pump = async () => {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!player) break;
      player.feed(value);
    }
    player?.close();
  };
  pump();
}

// --- 暂停播放 ---
function pause() {
  player?.pause(); // 暂停输出，但继续接收数据
}

// --- 恢复播放 ---
async function resume() {
  await player?.resume();
}

// --- 销毁实例 ---
function destroy() {
  player?.destroy(); // 释放内存、URL 对象和事件监听
  player = null;
}
```




## 📖 API 参考

### 构造函数选项 `AudioStreamPlayerOptions`

| 参数 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `mimeType` | `string` | 自动检测 | 音频 MIME 类型。推荐 `'audio/mp4'` (AAC) 或 `'audio/webm'` (Opus)。如果不指定，库会尝试检测浏览器支持的最佳格式。 |
| `autoplay` | `boolean` | `true` | 是否允许在缓冲足够时自动开始播放。 |
| `minBufferMs` | `number` | `300` | **关键参数**。启动播放所需的最小缓冲时长（毫秒）。值越小延迟越低，但抗网络波动能力越弱。 |
| `maxBufferMs` | `number` | `5000` | 最大缓冲时长。防止内存无限增长，超出部分旧数据会被丢弃。 |
| `audioElement` | `HTMLAudioElement` | 新建 | 可选。传入现有的 `<audio>` 标签，以便自定义样式或挂载其他事件。 |
| `onStateChange` | `Function` | - | 状态变更回调。参数为 `PlayerState`。 |
| `onError` | `Function` | - | 错误捕获回调。 |

### 实例方法

| 方法 | 说明 |
| :--- | :--- |
| `open(): Promise<void>` | 初始化 MediaSource 并准备接收数据。**必须在 feed 之前调用。** |
| `feed(data: Uint8Array \| ArrayBuffer)` | 向播放器投喂音频数据块。数据会自动存入环形缓冲区。 |
| `pause(): void` | 暂停音频输出。暂停后 `feed` 的数据仍会进入缓冲区，适合“插话”场景。 |
| `resume(): Promise<void>` | 从暂停状态恢复播放。 |
| `play(): Promise<void>` | 强制开始播放。通常用于处理浏览器自动播放策略限制。 |
| `close(): void` | 标记流结束。播放器会播完剩余缓冲后触发 `ended` 状态。 |
| `destroy(): void` | 彻底销毁实例，释放所有底层资源。组件卸载时务必调用。 |
| `getState(): PlayerState` | 获取当前播放状态。 |

### 状态枚举 `PlayerState`

*   `idle`: 初始状态。
*   `buffering`: 正在缓冲，尚未达到启动阈值。
*   `playing`: 正在播放。
*   `paused`: 用户主动暂停。
*   `ended`: 流已正常结束。
*   `error`: 发生错误。



## 🛠️ 贡献指南

如果你希望参与贡献或本地调试：

```bash
# 克隆仓库
git clone https://github.com/agents-flex/audioStreamPlayer.git
cd audioStreamPlayer

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产包
npm run build
```

## 📄 License

MIT © [yangfuhai](https://github.com/yangfuhai)

</div>
