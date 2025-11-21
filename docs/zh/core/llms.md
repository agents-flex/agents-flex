# LLMs 大语言模型

Agents-Flex 提供了关于大语言模型的抽象实现接口 `Llm.java`，它支持 `chat` 以及 `chatStream` 两种不同的对话方式。

针对不同的厂商，Agents-Flex 提供了不同的实现类以及通信协议，其中通信协议包括了 `HTTP`、`SSE` 以及 `WebSocket` 等客户端。

## 大模型支持

目前，Agents-Flex 已支持以下的大语言模型：

- OpenAI（ChatGPT，以及所有的兼容 OpenAI 接口的大模型）
- ChatGLM（智普大模型）
- Coze （调用 Coze 的智能体）
- DeepSeek
- Gitee AI
- Moonshot（月之暗面）
- Ollama（通过 Ollama 部署的所有大模型）
- Qianfan（百度千帆部署的大模型）
- Qwen（千问大模型，阿里云百炼平台部署的大模型）
- Spark（星火大模型）


## 简单对话
```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();

    // 设置你的 OpenAI API Key
    config.setApiKey("sk-rts5NF6n*******");

    Llm chatModel = new OpenAILlm(config);
    String response = chatModel.chat("请问你叫什么名字");

    System.out.println(response);
}
```

## 流式对话

```java
public static void main(String[] args) {

    OpenAILlmConfig config = new OpenAILlmConfig();
    // 设置你的 OpenAI API Key
    config.setApiKey("sk-rts5NF6n*******");

    Llm chatModel = new OpenAILlm(config);

    chatModel.chatStream("你叫什么名字", new StreamResponseListener() {

        @Override
        public void onMessage(ChatContext context, AiMessageResponse response) {
            System.out.println(response.getMessage().getContent());
        }

    });
}
```

## 流式对话之停止对话

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();

    // 设置你的 OpenAI API Key
    config.setApiKey("sk-rts5NF6n*******");

    Llm chatModel = new OpenAILlm(config);
    chatModel.chatStream("你叫什么名字", new StreamResponseListener() {
        @Override
        public void onMessage(ChatContext context, AiMessageResponse response) {
            System.out.println(response.getMessage().getContent());

            //停止对话
            context.getClient().stop();
        }
    });
}
```


## 流式对话之更多的监听

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();

    // 设置你的 OpenAI API Key
    config.setApiKey("sk-rts5NF6n*******");

    Llm chatModel = new OpenAILlm(config);
    chatModel.chatStream("你叫什么名字", new StreamResponseListener() {
        @Override
        public void onMessage(ChatContext context, AiMessageResponse response) {
            AiMessage message = response.getMessage();
            System.out.println(message.getContent());
        }

        @Override
        public void onStart(ChatContext context) {
            System.out.println("开始");
        }

        @Override
        public void onStop(ChatContext context) {
            System.out.println("结束");
        }

        @Override
        public void onFailure(ChatContext context, Throwable throwable) {
            System.out.println("错误");
        }
    });
}
```

## 图片识别对话

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();

    // 设置你的 OpenAI API Key
    config.setApiKey("sk-5gqOcl*****");
    config.setModel("gpt-4-turbo");


    Llm chatModel = new OpenAILlm(config);
    ImagePrompt prompt = new ImagePrompt("What's in this image?");
    prompt.addImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");

    //或者 prompt.setImageFile(new File("/your-image-path.png"))
    //或者 prompt.setImageStream(imageInputStream)
    //或者 prompt.setImageBase64("image base64 data....")

    AiMessageResponse response = chatModel.chat(prompt);
    System.out.println(response);
}
```

## 方法调用（Function Calling）

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAILlm chatModel = new OpenAILlm(config);

    FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
    AiMessageResponse response = chatModel.chat(prompt);

    System.out.println(response.callFunctions());
    // "Today it will be dull and overcast in 北京"
}
```

`WeatherFunctions.class` 代码如下：

```java
public class WeatherFunctions {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo( @FunctionParam(name = "city", description = "the city name") String name) {
        return "Today it will be dull and overcast in " + name;
    }
}
```


## 历史对话

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAILlm chatModel = new OpenAILlm(config);

    //第一步：创建一个 HistoriesPrompt
    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("您想问什么？");
    Scanner scanner = new Scanner(System.in);
    String userInput = scanner.nextLine();

    while (userInput != null) {

        // 第二步：将用户输入添加到 HistoriesPrompt 中
        prompt.addMessage(new HumanMessage(userInput));

        // 第三步：调用 chatStream 方法，进行对话
        chatModel.chatStream(prompt, (context, response) -> {
            System.out.println(">>>> " + response.getMessage().getContent());
        });

        userInput = scanner.nextLine();
    }
}
```

关于 HistoriesPrompt 更多的配置：

```java
HistoriesPrompt prompt = new HistoriesPrompt();

//设置系统消息
prompt.setSystemMessage(new SystemMessage('你是一个数据库开发工程师....'));

//设置最大历史消息数量
prompt.setMaxAttachedMessageCount(10);

//设置是否开启历史消息截断
prompt.setHistoryMessageTruncateEnable(true);

//设置历史消息截断长度
prompt.setHistoryMessageTruncateLength(1000);

//自定义历史消息截断处理器
prompt.setHistoryMessageTruncateProcessor(...);

//设置历史消息存储器
prompt.setMemory(...);
```

## 历史对话 + 方法调用

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAILlm chatModel = new OpenAILlm(config);

    //第一步：创建一个 HistoriesPrompt
    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("您想问什么？");
    Scanner scanner = new Scanner(System.in);
    String userInput = scanner.nextLine();

    while (userInput != null) {

        // 第二步：创建 HumanMessage，并添加方法调用
        HumanMessage userMessage = new HumanMessage(userInput);
        userMessage.addFunctions(WeatherFunctions.class);

        // 第三步：将 HumanMessage 添加到 HistoriesPrompt 中
        prompt.addMessage(userMessage);

        // 第四步：调用 chatStream 方法，进行对话
        chatModel.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                boolean toolCall = response.isFunctionCall();
                if (toolCall) {
                    System.out.println("do func >>> ");
                    StringBuilder text = new StringBuilder("调用工具结果如下：\n");
                    List<FunctionCaller> callers = response.getFunctionCallers();
                    for (FunctionCaller caller : callers) {
                        String name = caller.getFunction().getName();
                        Object callRes = caller.call();
                        text.append("调用[").append(name).append("]的结果为：").append(callRes).append("\n");
                    }
                    HumanMessage msg = new HumanMessage(text.toString());
                    prompt.addMessage(msg);
                    chatModel.chatStream(prompt, new StreamResponseListener() {
                        @Override
                        public void onMessage(ChatContext context, AiMessageResponse response) {
                            System.out.println("after func >>>> " + response.getMessage().getContent());
                        }
                    });
                } else {
                    System.out.println("normal >>>> " + response.getMessage().getContent());
                }
            }
        });

        userInput = scanner.nextLine();
    }
}
```
