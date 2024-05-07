# LLMs 大语言模型

Agents-Flex 提供了关于大语言模型的抽象实现接口 `Llm.java`，它支持 `chat` 以及 `chatStream` 两种不同的对话方式。

针对不同的厂商，Agents-Flex 提供了不同的实现类以及通信协议，其中通信协议包括了 `HTTP`、`SSE` 以及 `WebSocket` 等客户端。

## chat 对话

在 AI 大语言模型 chat 对话中，我们需要关注几个不同的场景，分别是：

- 简单对话
- 历史对话
- Function Calling 方法调用

而以上的能力又通过 prompt（提示词）来决定的，因此，Agents-Flex 提供了三种 prompt 的实现，分别是：

- SimplePrompt：用于简单对话的场景
- HistoriesPrompt：用于历史对话的场景
- FunctionPrompt：用于 Function Calling 的场景

而提示词和大模型交互的过程中，是需要通过消息来交互的，因此，Agents-Flex 也提供了不同的消息实现，他们分别是：

- AiMessage：大模型响应的消息，除了消息内容以外，还会带有消耗 token 的数据等。
- FunctionMessage：是 AiMessage 的子类，当我们在 chat 方法中传入 FunctionPrompt 时，返回的应该是 FunctionMessage。
- HumanMessage：人类消息，也就是在对话时用户输入的消息。
- SystemMessage：系统消息，常用于告知大语言模型的角色，用于 prompt 微调的场景。

### 示例代码

**简单对话**

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    Prompt prompt = new SimplePrompt("what is your name?");
    String response = llm.chat(prompt);

    System.out.println(response);
}
```

**历史对话**

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    HistoriesPrompt prompt = new HistoriesPrompt();
    prompt.addMessage(new SystemMessage("你现在是一个数据库开发工程师...."));
    prompt.addMessage(new HumanMessage("请根据 DDL 内容，给出...."));

    String response = llm.chat(prompt);

    System.out.println(response);
}
```

**Function Calling**

工具类定义：

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name) {
        //在这里，我们应该通过第三方接口调用 api 信息
        return name + "的天气是阴转多云。 ";
    }
}
```

创建 FunctionPrompt 通过 chat 方法传给大模型：

```java
public static void main(String[] args) {
    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherUtil.class);
    FunctionResultResponse response = llm.chat(prompt);

    //执行工具类方法得到结果
    Object result = response.invoke();

    System.out.println(result);
    //"北京的天气是阴转多云。 "
}
```
