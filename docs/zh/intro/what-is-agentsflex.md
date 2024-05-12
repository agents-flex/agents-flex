# Agents-Flex 是什么？

Agents-Flex 是一个 Java 开发的 AI 应用开发框架，是为了简化 AI 应用开发而生。 其灵感来源 LangChain、 LlamaIndex 以及作者作为一线 AI 应用开发工程师的最佳实践，提供了跨
AI 服务商的、可移植的、不限 Java 开发框架的 API 支持。

Agents-Flex 适用于聊天、图像生成、Embedding 模型、Function Calling 以及 RAG 应用等场景，支持同步以及流式（Stream）的 API 选择。

作者的其他开源框架，还包含了：
- MyBatis-Flex：https://mybatis-flex.com
- AiEditor：https://aieditor.dev
- AiAdmin：官网正在建设中...

## Agents-Flex 和其他框架对比

**1、更具有普适性**

相比 `Spring-AI`、`LangChain4j` 而言，Agents-Flex 更具有普适性。

> 1) 比如 `Spring-AI` 要求的 JDK 版本必须是 `JDK 21+`，而 Agents-Flex 只需要 `JDK8+`。
> 2) `Spring-AI` 要求必须在 Spring 框架下使用，而 Agents-Flex 支持与任何框架搭配，并提供了 `spring-boot-starter`。
> 3) `Spring-AI`、`LangChain4j` 普遍不支持国内的大模型、Embedding 模型以及向量数据库，而 Agents-Flex 对国产服务支持友好。

**2、更简易的 API 设计**

使用 Agents-Flex 两行代码即可实现聊天功能：

```java
@Test
public void testChat() {
    OpenAiLlm llm = new OpenAiLlm.of("sk-rts5NF6n*******");
    String response = llm.chat("what is your name?");

    System.out.println(response);
}
```

Function Calling 也只需要几行代码：

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name")String name ) {
        //在这里，我们应该通过第三方接口调用 api 信息
        return name + "的天气是阴转多云。 ";
    }


    public static void main(String[] args) {
        OpenAiLlm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherUtil.class);
        FunctionResultResponse response = llm.chat(prompt);

        Object result = response.invoke();

        System.out.println(result);
        //"北京的天气是阴转多云。 "
    }
}
```

**2、更强大的 Agents 编排**

我们知道，一个强大的 AI 应用，往往是需要灵活的编排能力来完成的， 相比 Agents-Flex 而言，`Spring-AI`、`LangChain4j`几乎没有编排的能力。

以下是一个简单的 Agents-Flex 关于 Chain（执行链） 编排示例代码：

```java
public static void main(String[] args) {
    SequentialChain ioChain1 = new SequentialChain();
    ioChain1.addNode(new Agent1("agent1"));
    ioChain1.addNode(new Agent2("agent2"));

    SequentialChain ioChain2 = new SequentialChain();
    ioChain2.addNode(new Agent1("agent3"));
    ioChain2.addNode(new Agent2("agent4"));
    ioChain2.addNode(ioChain1);

    ioChain2.registerEventListener(new ChainEventListener() {
        @Override
        public void onEvent(ChainEvent event, Chain chain) {
            System.out.println(event);
        }
    });


    Object result = ioChain2.executeForResult("your params");
    System.out.println(result);
}
```
以上代码实现了如下图所示的 Agents 编排：

![](../../assets/images/chians-01.png)

数据流向： agent3 --> agent4 --> chain1，而 chain1 内部又包含 agent1 --> agent2 的过程。


在 Agents-Flex 中，我们内置了 3 种不同的 Agents 执行链，他们分别是：

- SequentialChain：顺序执行链
- ParallelChain：并发（并行）执行链
- LoopChain：循环执行连


而以上 3 种执行链中，每个又可以作为其他执行链的子链进行执行，从而形成强大而复杂的 Agents 执行链条。

