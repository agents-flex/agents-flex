<p align="center">
    <img src="./docs/assets/images/banner.png"/>
</p>



# Agents-Flex: A Lightweight Java AI Application Development Framework


## Basic Capabilities

- LLM access capabilities
- LLM Chat interceptor
- Prompt and Prompt Template definition loading capabilities
- Tool method definition, invocation, and execution capabilities
- Tool method execution interceptor
- MCP invocation, response interceptor, monitoring, and caching
- Memory capabilities
- Embedding
- Vector Store
- file2text document reading
- splitter document segmentation
- Observability (based on OpenTelemetry)



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

    String output = chatModel.chat("How can I be more humorous?");
    System.out.println(output);
}
```

## documents

https://agentsflex.com

