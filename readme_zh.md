<h4 align="right"><a href="./readme.md">English</a> | <strong>简体中文</strong></h4>




# Agents-Flex： 一个优雅的 LLM（大语言模型） 应用开发


## Hello Word

使用 OpenAi 大语言模型:

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiConfig config = new OpenAiConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAiLlm(config);

    Prompt  prompt = new SimplePrompt("请写一个关于小兔子战胜大灰狼的故事。");
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```

使用 “讯飞星火” 大语言模型:

```java
 public static void main(String[] args) throws InterruptedException {

    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);

    Prompt  prompt = new SimplePrompt("请写一个关于小兔子战胜大灰狼的故事。");
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```
