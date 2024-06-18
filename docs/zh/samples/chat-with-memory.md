# 历史对话

## 示例代码

历史对话 和 简单对话类似，只需要把 prompt 修改为 `HistoriesPrompt` 即可，代码如下：

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    HistoriesPrompt prompt = new HistoriesPrompt();
    pormpt.addMessage(new HumanMessage("你叫什么名字？"));

    String response = llm.chat(prompt);
    System.out.println(response);
}
```

## 连续对话

```java
public static void main(String[] args) {
    System.out.println("请开始向 AI 提问！");

    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");
    HistoriesPrompt prompt = new HistoriesPrompt();

    Scanner scanner = new Scanner(System.in);
    String userInput = scanner.nextLine();
    while (userInput != null) {

        prompt.addMessage(new HumanMessage(userInput));

        llm.chatStream(prompt, (context, response) -> {
            System.out.println(">>>> " + response.getMessage().getContent());
        });

        //等待用户输入
        userInput = scanner.nextLine();
    }
}
```

在以上的连续对话中，`HistoriesPrompt` 有一个成员变量 `memory` 用于存储所有的历史对话内容。
默认的情况下，是使用内存进行存储，当我们需要对对话内容进行持久化时，只需要实现自己的 Memory 即可，示例代码如下：

```java
public class DatabaseChatMemory implements ChatMemory {

    @Override
    public List<Message> getMessages() {
        //从数据库查询所有的历史消息
        return Db.findList("select * from ....");
    }

    @Override
    public void addMessage(Message message) {
        //把消息添加到数据库
        Db.save(message);
    }
}
```

然后，在创建 `HistoriesPrompt` 时，传入自己的 `DatabaseChatMemory`，如下代码所示：

```java
public static void main(String[] args) {

    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    HistoriesPrompt prompt = new HistoriesPrompt(new DatabaseChatMemory());
    prompt.addMessage(new HumanMessage("user new question...."));

    llm.chat(prompt);
    //....
}
```
