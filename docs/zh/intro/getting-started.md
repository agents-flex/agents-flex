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
    <version>1.2.2</version>
</dependency>
```

或者使用 Gradle:

```java
implementation 'com.agentsflex:agents-flex-bom:1.2.2'
```

**第 2 步：创建一个带有 Main 方法的 Java 类**

```java
public class Main {
    public static void main(String[] args) {
        Llm llm = OpenAILlm.of("sk-rts5NF6n*******");
        String response = llm.chat("what is your name?");

        System.out.println(response);
    }
}
```


或者，我们为 `OpenAILlm` 添加更多的配置：

```java
public class Main {
    public static void main(String[] args) {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setEndpoint("https://api.openai.com");
        config.setApiKey("sk-rts5NF6n*******");
        config.setModel("gpt-3.5-turbo");

        Llm llm = new OpenAILlm(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }
}
```


## 流式（Stream）对话

流式（Stream）对话需要调用 `chatStream` 方法，并传入 prompt 以及 `StreamResponseListener`，代码如下所示：

```java
public class Main {
    public static void main(String[] args) {
        Llm llm = new OpenAILlm.of("sk-rts5NF6n*******");

        llm.chatStream("what is your name?", new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }

        });

        System.out.println(response);
    }
}
```
