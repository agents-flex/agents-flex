<h4 align="right"><a href="./readme.md">English</a> | <strong>简体中文</strong></h4>




# Agents-Flex： 一个优雅的 LLM（大语言模型） 应用开发框架


## 简单对话

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

使用 “通义千问” 大语言模型:

```java
 public static void main(String[] args) throws InterruptedException {

    QwenLlmConfig config = new QwenLlmConfig();
    config.setApiKey("sk-28a6be3236****");
    config.setModel("qwen-turbo");

    Llm llm = new QwenLlm(config);

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

## 历史对话示例


```java
 public static void main(String[] args) throws InterruptedException {

    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    // 创建一个大模型
    Llm llm = new SparkLlm(config);

    //创建一个历史对话的 prompt
    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("您想问什么？");
    Scanner scanner = new Scanner(System.in);

    //等待用户从控制台输入问题
    String userInput = scanner.nextLine();

    while (userInput != null){

        prompt.addMessage(new HumanMessage(userInput));

        //向大模型提问
        llm.chat(prompt, (instance, message) -> {
            System.out.println(">>>> " + message.getContent());
        });

        //继续等待用户从控制台输入内容
        userInput = scanner.nextLine();
    }
}
```
