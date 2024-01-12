<h4 align="right"><strong>English</strong> | <a href="./readme_zh.md">简体中文</a></h4>



# Agents-Flex is an elegant LLM Application Framework like LangChain with Java.


## Hello Word

use OpenAi LLM:

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiConfig config = new OpenAiConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAiLlm(config);

    Prompt  prompt = new SimplePrompt("Please write a story about a little rabbit defeating a big bad wolf");
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```

use SparkAi LLM:

```java
 public static void main(String[] args) throws InterruptedException {

    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);

    Prompt  prompt = new SimplePrompt("Please write a story about a little rabbit defeating a big bad wolf");
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```

