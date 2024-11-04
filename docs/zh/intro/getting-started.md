# 快速开始

## 前言

在开始之前，我们假定您已经：

- 熟悉 Java 环境配置及其开发
- 熟悉 Java 构建工具，比如 Maven

> 注意：需要确定您当前的环境必须是 Java 8 或者更高版本。

## Hello World

**第 1 步：创建 Java 项目，并添加 Maven 依赖**

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>1.0.0-rc.0</version>
</dependency>
```

或者使用 Gradle:

```java
implementation 'com.agentsflex:agents-flex-bom:1.0.0-rc.0'
```

**第 2 步：创建一个带有 Main 方法的 Java 类**

```java
public class Main {
    public static void main(String[] args) {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAiLlm(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }
}
```

以上代码可以通过 `OpenAiLlm.of` 简化创建 LLM 对象：


```java
public class Main {
    public static void main(String[] args) {
        Llm llm = OpenAiLlm.of("sk-rts5NF6n*******");
        String response = llm.chat("what is your name?");

        System.out.println(response);
    }
}
```

或者使用科大讯飞的星火大模型：

```java
public class Main {
    public static void main(String[] args) {
        SparkLlmConfig config = new SparkLlmConfig();
        config.setAppId("****");
        config.setApiKey("****");
        config.setApiSecret("****");

        Llm llm = new SparkLlm(config);
        String response = llm.chat("请问你叫什么名字");
    }
}
```

## 流式（Stream）对话

流式（Stream）对话需要调用 `chatStream` 方法，并传入 prompt 以及 `StreamResponseListener`，代码如下所示：

```java
public class Main {
    public static void main(String[] args) {
        Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

        llm.chatStream("what is your name?", new StreamResponseListener<AiMessageResponse, AiMessage>() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(">>>> " + response.getMessage().getContent());
            }
        });

        System.out.println(response);
    }
}
```
