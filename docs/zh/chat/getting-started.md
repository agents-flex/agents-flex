# 快速开始

## 前言

在开始之前，我们假定您已经：

- 熟悉 Java 环境配置及其开发
- 熟悉 Java 构建工具，比如 Maven

> 注意：需要确定您当前的环境必须是 Java 8 或者更高版本。

## Hello World

**第 1 步：创建 Java 项目，并添加 Maven 依赖**

<span style="color: red;">**以下的 xml maven 依赖示例中，可能并非最新的 Agents-Flex 版本**</span>，请自行查看最新版本，并修改版本号。最新版本查看地址：https://search.maven.org/artifact/com.agentsflex/parent

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>2.0.0</version>
</dependency>
```

或者使用 Gradle:

```java
implementation 'com.agentsflex:agents-flex-bom:2.0.0'
```


### 2. 创建模型配置（以 OpenAI 为例）
```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey("your-api-key");
config.setModel("gpt-4o");
```

### 3. 实例化 ChatModel
```java
ChatModel chatModel = new OpenAIChatModel(config);
```

### 4. 同步调用（简单文本）
```java
String response = chatModel.chat("你好，今天过得怎么样？");
System.out.println(response); // 输出完整回复
```

### 5. 流式调用（实时逐片段接收）
```java
chatModel.chatStream("请用 Java 写一个单例模式", new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        // 使用 fullContent 获取当前已接收的完整内容
        String fullText = response.getMessage().getFullContent();
        String delta = response.getMessage().getContent(); // 仅本次增量

        System.out.print(delta); // 实时输出增量（更流畅）
        // 或 System.out.println(fullText); // 每次输出完整内容（覆盖式）
    }

    @Override
    public void onStart(StreamContext context) {
        System.out.println("[流式开始]");
    }

    @Override
    public void onStop(StreamContext context) {
        System.out.println("\n[流式正常结束]");
    }

    @Override
    public void onFailure(StreamContext context, Throwable throwable) {
        System.err.println("流式调用失败: " + throwable.getMessage());
    }
});
```
