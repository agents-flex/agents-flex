# Large Language Models (LLMs)


Agents-Flex provides an abstract implementation interface for Large Language Models (LLMs) called `Llm.java`, supporting two different dialogue modes: `chat` and `chatStream`.

For various vendors, Agents-Flex offers different implementation classes and communication protocols, including `HTTP`, `SSE`, and `WebSocket` clients.

## Chat dialogue

In AI large language model chat conversations, we need to consider several different scenarios:

- Simple conversation
- Historical conversation
- Function Calling

These capabilities are determined by prompts, so Agents-Flex provides three implementations of prompts:

- SimplePrompt: Used for simple conversation scenarios
- HistoriesPrompt: Used for historical conversation scenarios
- FunctionPrompt: Used for Function Calling scenarios

During the interaction between prompts and large models, messages are exchanged. Therefore, Agents-Flex also provides different message implementations:

- AiMessage: The message returned by the large model, which includes not only the message content but also data such as token consumption.
- FunctionMessage: A subclass of AiMessage, returned when using FunctionPrompt in the chat method.
- HumanMessage: Represents messages input by users during conversations.
- SystemMessage: Represents system messages, often used to inform the large language model's role, for fine-tuning prompts.

### Example Code

**Simple Dialogue**

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    Prompt prompt = new SimplePrompt("what is your name?");
    String response = llm.chat(prompt);

    System.out.println(response);
}
```

**Historical Dialogue**

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
        //Here, we should retrieve API information through third-party interfaces.
        return name + "The weather is cloudy turning to overcast. ";
    }
}
```

Create FunctionPrompt and pass it to the large model through the chat method:

```java
public static void main(String[] args) {
    OpenAiLlmConfig config = new OpenAiLlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAiLlm llm = new OpenAiLlm(config);

    FunctionPrompt prompt = new FunctionPrompt("Today, how's the weather in New York?", WeatherUtil.class);
    FunctionResultResponse response = llm.chat(prompt);

    //Execute utility class method to obtain result.
    Object result = response.invoke();

    System.out.println(result);
    //"The weather in New York is cloudy turning overcast."
}
```
