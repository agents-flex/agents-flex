<h4 align="right"><strong>English</strong> | <a href="./readme_zh.md">简体中文</a></h4>



# Agents-Flex is an elegant LLM Application Framework like LangChain with Java.

---

## Features

- LLM Visit
- Prompt、Prompt Template Loader
- Function Calling Definer, Invoker、Running
- Memory
- Embedding
- Vector Storage
- Resource Loaders
- Document
  - Splitter
  - Loader
  - Parser
    - PoiParser
    - PdfBoxParser
- LLMs Chain
- Agents Chain

## Simple Chat

use OpenAi LLM:

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiConfig config = new OpenAiConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAiLlm(config);

    String prompt = "Please write a story about a little rabbit defeating a big bad wolf";
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```


use Qwen LLM:

```java
 public static void main(String[] args) throws InterruptedException {

    QwenLlmConfig config = new QwenLlmConfig();
    config.setApiKey("sk-28a6be3236****");
    config.setModel("qwen-turbo");

    Llm llm = new QwenLlm(config);

    String  prompt = "Please write a story about a little rabbit defeating a big bad wolf";
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

    String  prompt = "Please write a story about a little rabbit defeating a big bad wolf";
    llm.chat(prompt, (llmInstance, message) -> {
        System.out.println("--->" + message.getContent());
    });

    Thread.sleep(10000);
}
```

## Chat With Histories


```java
 public static void main(String[] args) {

    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    // Create LLM
    Llm llm = new SparkLlm(config);

    // Create Histories prompt
    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("ask for something...");
    Scanner scanner = new Scanner(System.in);

    //wait for user input
    String userInput = scanner.nextLine();

    while (userInput != null){

        prompt.addMessage(new HumanMessage(userInput));

        //chat with llm
        llm.chat(prompt, (instance, message) -> {
            System.out.println(">>>> " + message.getContent());
        });

        //wait for user input
        userInput = scanner.nextLine();
    }
}
```

## Function Calling

- step 1: define the function native

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        //we should invoke the third part api for weather info here
        return "Today it will be dull and overcast in " + name;
    }
}

```

- step 2: invoke the function from LLM

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    Functions<String> functions = Functions.from(WeatherUtil.class, String.class);
    String result = llm.call("How is the weather in Beijing today?", functions);

    System.out.println(result);
    // "Today it will be dull and overcast in Beijing";

    Thread.sleep(10000);
}
```


## Communication

![](./docs/assets/images/wechat-group.jpg)

## Modules

![](./docs/assets/images/modules.jpg)
