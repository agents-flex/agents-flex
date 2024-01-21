<h4 align="right"><a href="./readme.md">English</a> | <strong>简体中文</strong></h4>




# Agents-Flex： 一个优雅的 LLM（大语言模型） 应用开发框架

## 能力

- LLM 的访问能力
- Prompt、Prompt Template 定义加载的能力
- Function Calling 定义、调用和执行等能力
- Embedding
- Vector Storage
- 丰富的内容加载器
- 丰富的文本分割器
- LLM Chain
- Agents Chain

## 简单对话

使用 OpenAi 大语言模型:

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiConfig config = new OpenAiConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAiLlm(config);

    String prompt = "请写一个关于小兔子战胜大灰狼的故事。";
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

    String prompt = "请写一个关于小兔子战胜大灰狼的故事。";
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

    String prompt = "请写一个关于小兔子战胜大灰狼的故事。";
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



## Function Calling

- 第一步: 通过注解定义本地方法

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        //在这里，我们应该通过第三方接口调用 api 信息
        return name + "的天气是阴转多云。 ";
    }
}

```

- 第二步: 通过 Prompt、Functions 传入给大模型，然后得到结果

```java
 public static void main(String[] args) throws InterruptedException {

    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    Functions<String> functions = Functions.from(WeatherUtil.class, String.class);
    String result = llm.call("今天北京的天气如何", functions);

    System.out.println(result);
    // "北京的天气是阴转多云。 ";

    Thread.sleep(10000);
}
```

## 交流群

![](./docs/assets/images/wechat-group.jpg)
