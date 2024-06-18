# 简单对话

## 示例代码

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    Prompt prompt = new SimplePrompt("what is your name?");
    String response = llm.chat(prompt);

    System.out.println(response);
}
```
