# 简单对话

## 示例代码

```java
public static void main(String[] args) {
    Llm chatModel = new OpenAILlm.of("sk-rts5NF6n*******");

    Prompt prompt = new SimplePrompt("what is your name?");
    String response = chatModel.chat(prompt);

    System.out.println(response);
}
```
## 流式请求

```java
void streamChat(String systemMessage,String userMessage){
        //创建大模型提示词输入，更多的提示词类型请参考文档“Prompt 提示词”部分。
        TextPrompt textPrompt = new TextPrompt();
        //创建系统提示词（可选）
        SystemMessage sysMsg = new SystemMessage();
        sysMsg.setContent(systemMessage);
        textPrompt.setSystemMessage(sysMsg);
        //传入用户问题
        textPrompt.setContent(userMessage);
        //模型选项（可选）
        ChatOptions chatOptions = new ChatOptions();
        chatOptions.setModel("deepseek-r1:32b"); //设置模型ID，根据使用的LLM模型提供方进行配置
        chatOptions.setTemperature(0.65F); //模型温度
        chatOptions.setMaxTokens(4096); //模型可接收上下文长度，请根据实际进行配置。
        ... //更多配置参考ChatOptions提供的方法。
        //创建流式监听器,建议单独管理。
        StreamResponseListener listener = new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                //在这里来处理模型返回的数据。
                //content是流式返回的每一个token字符串。
                String content = response.getMessage().getContent();
                //reasoningContent是思考模型返回的每一个token字符串。
                String reasoningContent = response.getMessage().getReasoningContent();
                /**
                 * 在调用思考模型的时候要注意，每个模型提供方返回的思考过程返回的标签不一样。
                 * 例如OpenAI通用协议的思考返回和Ollama的思考返回不一致。
                 * 项目已经对支持的模型提供方进行了处理，您需要在使用时根据实际情况选择将content还是reasoningContent返回给用户。
                 * 接下来就可以在这里根据您的项目具体需要，选择Flux或是Websocket方式返回给前端。
                 */

            }
        };
        sparkLlm.chatStream(textPrompt,listener,chatOptions);
    }
