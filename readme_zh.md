<p align="center">
    <img src="./docs/assets/images/banner.png"/>
</p>



# Agents-Flex： 一个轻量的 Java AI 应用开发框架

---

## 基本能力

- LLM 的访问能力
- LLM Chat 拦截器
- Prompt、Prompt Template 定义加载的能力
- Tool 方法定义、调用和执行等能力
- Tool 方法执行拦截器
- MCP 调用、响应拦截器、监控、缓存
- Memory 记忆的能力
- Embedding
- Vector Store
- file2text 文档读取
- splitter 文档分割
- 可观测（基于 OpenTelemetry）



## Hello World

```java
public static void main(String[] args) {
    OpenAIChatModel chatModel = OpenAIChatConfig.builder()
        .provider("GiteeAI")
        .endpoint("https://ai.gitee.com")
        .requestPath("/v1/chat/completions")
        .apiKey("P****QL7D12")
        .model("Qwen3-32B")
        .buildModel();

    String output = chatModel.chat("如何才能更幽默?");
    System.out.println(output);
}
```

控制台输出：

```text
[GiteeAI/Qwen3-32B] >>>> request: {"temperature":0.5,"messages":[{"content":"如何才能更幽默?","role":"user"}],"model":"Qwen3-32B"}
[GiteeAI/Qwen3-32B] <<<< response: {"id":"chatcmpl-46a3bacc483d4e5cb73c75061579fc00","object":"chat.completion","created":1764148724,"model":"Qwen/Qwen3-32B","choices":[{"index":0,"message":{"role":"assistant","reasoning_content...
幽默是一种让人愉悦的社交能力，它不仅能活跃气氛，还能拉近人与人之间的距离.....
```

第一行和第二行是 Agents-Flex  框架的日志信息，第三行是 `System.out.println(output)` 的输出 。


## Star 用户专属交流群

![](./docs/assets/images/wechat-group.jpg)


