# Prompt 提示词

在 Agents-Flex 中，提示词（Prompt）是程序和大模型交互的 “编程语言”，是让大语言模型理解的指令。

目前，Agents-Flex 支持一下几种类型的提示词：

- **TextPrompt**： 文本提示词，用于向大语言模型发送文本指令。
- **FunctionPrompt**： 函数提示词，用于向大语言模型发送带有 Function Calling 的提示词。
- **ImagePrompt**： 图像提示词，用于向大语言模型发送带有图像的提示词。
- **ToolPrompt**： 工具提示词，当本地执行完 Function 后，告知大模型结果的提示词。
- **HistoriesPrompt**： 带有历史记录的提示词，用于向大语言模型发送历史信息。

## TextPrompt

TextPrompt 是最基本的提示词，用于向大语言模型发送文本指令。

示例代码：

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm chatModel = new OpenAILlm(config);

    TextPrompt prompt = new TextPrompt("请问你叫什么名字");
    String response = chatModel.chat(prompt);

    System.out.println(response);
}
```

## FunctionPrompt

FunctionPrompt 用于向大语言模型发送带有 Function Calling 的提示词。

示例代码：

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

## ImagePrompt

ImagePrompt 用于向大语言模型发送带有图像的提示词。

示例代码：

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-5gqOcl*****");
    config.setModel("gpt-4-turbo");
    Llm chatModel = new OpenAILlm(config);

    ImagePrompt prompt = new ImagePrompt("What's in this image?");
    prompt.addImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");
    AiMessageResponse response = chatModel.chat(prompt);

    System.out.println(response);
}
```

## ToolPrompt

ToolPrompt 用于向大语言模型发送带有工具调用的提示词。

示例代码：

```java
public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAILlm chatModel = new OpenAILlm(config);

    FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
    AiMessageResponse response = chatModel.chat(prompt);

    if (response.isFunctionCall()) {
        AiMessageResponse response1 = chatModel.chat(ToolPrompt.of(response));
        System.out.println(response1.getMessage().getContent());
    } else {
        System.out.println(response);
    }
}
```

## HistoriesPrompt

HistoriesPrompt 用于向大语言模型发送带有历史对话的提示词。

示例代码：

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

## TextPromptTemplate

TextPromptTemplate 文本提示词模板，用于格式化提示词内容。

示例代码：

```java
public static void main(String[] args) {
    Map<String, Object> map = new HashMap<>();
    map.put("useName", "Michael");
    map.put("aaa", "星期3");

    TextPromptTemplate promptTemplate = TextPromptTemplate.create("你好 {{  useName }}，今天是星期: {{aaa   }}");
    String string = promptTemplate.formatToString(map);

    System.out.println(string);
    //输出： 你好 Michael，今天是星期: 星期3
}
```

在 `{\{}}` 双括号语法中，支持通过 `.` 访问对象属性，或者访问 map 的子 map 等，也支持通过 `??` 设置默认值。

示例代码：

```java
public static void main(String[] args) {
    String templateStr = "你好 {{ user.name ?? '匿名' }}，" +
        "欢迎来到 {{ site ?? 'AgentsFlex.com' }}！";
    TextPromptTemplate template = new TextPromptTemplate(templateStr);

    Map<String, Object> params = new HashMap<>();
    params.put("site", "AIFlowy.tech");

    Map<String, Object> user = new HashMap<>();
    user.put("name", "Michael");
    params.put("user", user);

    System.out.println(template.format(params));
    // 输出：你好 Michael，欢迎来到 AIFlowy.tech！

    System.out.println(template.format(new HashMap<>()));
    // 输出：你好 匿名，欢迎来到 AgentsFlex.com！
}
```

### 模板缓存

每次执行 `new TextPromptTemplate(templateStr)` 时，都会创建一个新的模板对象，同时会进行模板解析，有一定的性能开销，所以建议使用模板缓存，减少模板解析的次数。

在 Agents-Flex 中，内置了 `TextPromptTemplate.of(templateStr)` 方法，用于缓存模板对象，避免重复解析模板。

示例代码：

```java
public static void main(String[] args) {
    String templateStr = "你好 {{ user.name ?? '匿名' }}，" +
        "欢迎来到 {{ site ?? 'AgentsFlex.com' }}！";
    TextPromptTemplate template = TextPromptTemplate.of(templateStr);

    Map<String, Object> params = new HashMap<>();
    params.put("site", "AIFlowy.tech");

    Map<String, Object> user = new HashMap<>();
    user.put("name", "Michael");
    params.put("user", user);

    System.out.println(template.format(params));
    // 输出：你好 Michael，欢迎来到 AIFlowy.tech！

    System.out.println(template.format(new HashMap<>()));
    // 输出：你好 匿名，欢迎来到 AgentsFlex.com！
}
```
