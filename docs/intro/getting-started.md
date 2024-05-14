# Quickstart

## Introduction

Before you begin, ensure you are:

- Familiar with Java environment setup and development
- Familiar with Java build tools like Maven

> Note: Your environment must be Java 8 or higher.

## Hello World

**Step 1: Create a Java project and add the Maven dependency:**

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>1.0.0-beta.3</version>
</dependency>
```

Or use Gradle:

```java
implementation 'com.agentsflex:agents-flex-bom:1.0.0-beta.3'
```

**Step 2: Create a Java class with a Main method:**

```java
public class Main {
    public static void main(String[] args) {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAiLlm(config);
        String response = llm.chat("What is your name?");

        System.out.println(response);
    }
}
```

Simplify LLM object creation using `OpenAiLlm.of`:


```java
public class Main {
    public static void main(String[] args) {
        Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");
        String response = llm.chat("what is your name?");

        System.out.println(response);
    }
}
```

For Spark model:

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

## Streaming Conversation

Streaming dialogue requires calling the `chatStream` method, and passing the prompt along with `StreamResponseListener`, as shown in the code below:

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
