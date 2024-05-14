# Large Language Models (LLMs)


Agents-Flex provides an abstract implementation interface for Large Language Models (LLMs) called `Llm.java`,
supporting two different chat types: `chat` and `chatStream`.

For various vendors, Agents-Flex offers different implementation classes and communication protocols, including `HTTP`, `SSE`, and `WebSocket` clients.

## Chat

In chat conversations of AI LLMs, we need to consider several different scenarios:

- Simple chat
- Chat with Histories
- Function Calling

These capabilities are determined by prompts, so Agents-Flex provides three implementations of prompts:

- SimplePrompt: Used for simple chat
- HistoriesPrompt: Used for chat with Histories
- FunctionPrompt: Used for Function Calling

During the interaction between prompts and LLMs, messages are exchanged. Therefore, Agents-Flex also provides different message implementations:

- **AiMessage**: The message returned by the LLMs, which includes not only the message content but also data such as token consumption.
- **FunctionMessage**: A subclass of AiMessage, returned when using FunctionPrompt in the `chat` method.
- **HumanMessage**: Represents messages input by Human during conversations.
- **SystemMessage**: Represents system messages, often used to inform the LLM's role, for fine-tuning prompts.

### Example Code

**Simple Chat**

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    Prompt prompt = new SimplePrompt("what is your name?");
    String response = llm.chat(prompt);

    System.out.println(response);
}
```

**Chat with Histories**

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    HistoriesPrompt prompt = new HistoriesPrompt();
    prompt.addMessage(new SystemMessage("You are now a database development engineer...."));
    prompt.addMessage(new HumanMessage("Please provide the DDL content for...."));

    String response = llm.chat(prompt);

    System.out.println(response);
}
```

**Function Calling**

Utility class definition：

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name) {
        //Here, we should call API through third-party interfaces.
        return name + "The weather is cloudy turning to overcast. ";
    }
}
```

Create FunctionPrompt and pass it to the LLMs through the `chat` method:

```java
public static void main(String[] args) {
    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    FunctionPrompt prompt = new FunctionPrompt("how's the weather in New York?", WeatherUtil.class);
    FunctionResultResponse response = llm.chat(prompt);

    //Execute utility class method to obtain result.
    Object result = response.invoke();

    System.out.println(result);
    //"The weather in New York is cloudy turning overcast."
}
```
