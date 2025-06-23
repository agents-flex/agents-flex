# Agents-Flex 是什么？

Agents-Flex 是一个 Java 开发的 AI 应用开发框架，是为了简化 AI 应用开发而生。 其灵感来源 LangChain、 LlamaIndex 以及作者作为一线 AI 应用开发工程师的最佳实践，提供了跨
AI 服务商的、可移植的、可编排、不限 Java 开发框架的 API 支持。

Agents-Flex 适用于聊天、图像生成、音频生成、视频生成、Embedding 模型、Function Calling 以及 RAG 应用、智能体编排等场景，支持同步以及流式（Stream）的 API 选择。

作者的其他开源框架，还包含了：
- MyBatis-Flex：https://mybatis-flex.com (一个优雅的 MyBatis ORM 增强框架)
- AiEditor：https://aieditor.com.cn （一个面向 AI 的下一代富文本编辑器）
- Tinyflow ：https://tinyflow.cn (一个 AI 工作流编排解决方案、类似 Dify、Coze、腾讯元器等产品的 AI 工作流编排功能)
- AIFlowy：https://aiflowy.tech (一个基于 Java 开发的企业级的开源 AI 应用开发平台，整合了 MyBatis-Flex、Tinyflow、Agents-Flex 等框架，可以看作是以上开源产品的最佳实践)

## Agents-Flex 和其他框架对比

### 1、更具有普适性

相比 `Spring-AI`、`LangChain4j` 而言，Agents-Flex 更具有普适性。

> 1) `Spring-AI` 要求的 JDK 版本必须是 `JDK 17+`，而 Agents-Flex 只需要 `JDK 8+`。
> 2) `Spring-AI` 要求必须在 Spring 框架下使用，而 Agents-Flex 支持与任何的 JAVA 框架搭配使用，并提供了 `spring-boot-starter` 的支持。
> 3) `Spring-AI`、`LangChain4j` 普遍不支持国内的大模型、Embedding 模型以及向量数据库，而 Agents-Flex 对国产模型更加友好。

### 2、更简易的 API 设计

使用 Agents-Flex 两行代码即可实现聊天功能：

```java
@Test
public void testChat() {
    OpenAILlm llm = new OpenAILlm.of("sk-rts5NF6n*******");
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
        OpenAILlm llm = new OpenAILlm.of("sk-rts5NF6n*******");

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherUtil.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        //"北京的天气是阴转多云。 "
    }
}
```

### 3、更强大的智能体编排

我们知道，一个强大的 AI 应用，往往是需要灵活的编排能力来完成的， 相比 Agents-Flex 而言，`Spring-AI`、`LangChain4j` 几乎没有编排的能力。



### 4、开源地址

Gitee：https://gitee.com/agents-flex/agents-flex
Github: https://github.com/agents-flex/agents-flex
