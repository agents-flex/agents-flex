<h4 align="right"><a href="./readme.md">English</a> | <strong>简体中文</strong></h4>

<p align="center">
    <img src="./docs/assets/images/banner.png"/>
</p>



# Agents-Flex： 一个基于 Java 的 LLM（大语言模型）应用开发框架。

---

## 基本能力

- LLM 的访问能力
- Prompt、Prompt Template 定义加载的能力
- Function Calling 定义、调用和执行等能力
- 记忆的能力（Memory）
- Embedding
- Vector Store
- 文档处理
  - 加载器（Loader）
    - Http
    - FileSystem
  - 分割器（Splitter）
  - 解析器（Parser）
    - PoiParser
    - PdfBoxParser
- Agent
  - LLM Agent
  - IOAgent
- Chain 执行链
    - SequentialChain 顺序执行链
    - ParallelChain 并发（并行）执行链
    - LoopChain 循环执行连
    - ChainNode
        - AgentNode Agent 执行节点
        - EndNode 终点节点
        - RouterNode 路由节点
          - GroovyRouterNode Groovy 规则路由
          - QLExpressRouterNode QLExpress 规则路由
          - LLMRouterNode LLM路由（由 AI 自行判断路由规则）

## 简单对话

使用 OpenAi 大语言模型:

```java
 @Test
public void testChat() {
    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAiLlm(config);
    String response = llm.chat("请问你叫什么名字");

    System.out.println(response);
}
```

使用 “通义千问” 大语言模型:

```java
@Test
public void testChat() {
    QwenLlmConfig config = new QwenLlmConfig();
    config.setApiKey("sk-28a6be3236****");
    config.setModel("qwen-turbo");

    Llm llm = new QwenLlm(config);
    String response = llm.chat("请问你叫什么名字");

    System.out.println(response);
}
```



使用 “讯飞星火” 大语言模型:

```java
@Test
public void testChat() {
    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);
    String response = llm.chat("请问你叫什么名字");

    System.out.println(response);
}
```

## 历史对话示例


```java
public static void main(String[] args) {
    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);

    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("您想问什么？");
    Scanner scanner = new Scanner(System.in);
    String userInput = scanner.nextLine();

    while (userInput != null) {

        prompt.addMessage(new HumanMessage(userInput));

        llm.chatStream(prompt, (context, response) -> {
            System.out.println(">>>> " + response.getMessage().getContent());
        });

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
 public static void main(String[] args) {

    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherUtil.class);
    FunctionResultResponse response = llm.chat(prompt);

    Object result = response.invoke();

    System.out.println(result);
    //"北京的天气是阴转多云。 "
}
```

## 交流群

![](./docs/assets/images/wechat-group.png)



## 模块构成

![](./docs/assets/images/modules.jpg)
