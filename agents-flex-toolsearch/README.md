# Agents-Flex Tool Search

`agents-flex-toolsearch` provides progressive tool discovery for applications with dozens or hundreds of tools. Its default provider keeps metadata in memory and performs a weighted O(n) scan, with no Lucene, Elasticsearch, vector store, or embedding dependency.

## Usage

`ToolSearchTool` follows the same builder style as `WikiTool`. A built instance is also the isolation boundary: its manager, provider, registered tools, and discovered tools are independent from other instances.

```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.setSystemMessage(
    "Use toolSearch when you need a capability that is not currently visible."
);
prompt.addUserMessage("Check the weather and notify me");
prompt.addTools(alwaysVisibleTools);

ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(allApplicationTools)
    .prompt(prompt)
    .build();

AiMessageResponse response = chatModel.chat(prompt);
while (response.hasToolCalls()) {
    prompt.addMessage(response.getMessage());
    prompt.addMessages(response.executeToolCallsAndGetToolMessages());
    response = chatModel.chat(prompt);
}
```

Configuring `prompt(prompt)` binds the tool during `build()`. The first request exposes the prompt's always-visible tools plus `toolSearch`. When the model executes a search, matching executable tools are added to that prompt for the next model call. Each search replaces the previous search results; an empty result removes all previously discovered tools. Call `toolSearch.reset()` to return to the search-only state without running another search.

Tools added directly to the prompt are always visible: they continue to be sent to the model and are not registered in the search provider. This also applies to tools added to or removed from the prompt after `ToolSearchTool` is built. The binding reconciles developer-managed tools before every search, reset, and unbind operation. Only tools passed through the builder's `addTool` or `addTools` methods belong to the searchable catalog.

The same instance can be bound later with `toolSearch.bind(prompt)`. A `ToolSearchTool` can bind only one prompt at a time. Call `toolSearch.unbind()` before reusing it with another prompt; unbinding restores the original prompt's always-visible tools.

## Custom provider

`ToolSearchProvider` is both the metadata storage and search SPI. The default is `InMemoryToolSearchProvider`.

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .provider(new MyToolSearchProvider())
    .addTools(allApplicationTools)
    .prompt(prompt)
    .build();
```

Custom providers can use a database, Lucene, Elasticsearch, or semantic search. They store only `ToolInfo`; executable callbacks remain local in `ToolSearchManager`.

Use the metadata overload for categories, tags, or custom attributes:

```java
ToolInfo info = ToolInfo.from(weatherTool);
info.setCategory("weather");
info.setTags(Arrays.asList("forecast", "temperature"));

ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTool(weatherTool, info)
    .prompt(prompt)
    .build();
```

To deliberately share one catalog across multiple tool instances, configure the same manager with `.manager(manager)`. A builder cannot accept both `manager` and `provider`, because the manager already owns its provider.
