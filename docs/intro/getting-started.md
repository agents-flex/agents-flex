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
    <version>1.0.0-beta.10</version>
</dependency>
```

Or use Gradle:

```java
implementation 'com.agentsflex:agents-flex-bom:1.0.0-beta.10'
```

**Step 2: Create a Java class with a `main` method:**

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


## Streaming Conversation

Streaming Conversation requires calling the `chatStream` method, and passing the prompt with `StreamResponseListener` object, as shown in the code below:

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
