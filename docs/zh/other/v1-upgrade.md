# Agents-Flex v1.x 升级文档
<div v-pre>


本指南帮助用户从 v1 升级到 v2.x 版本，涵盖模块调整、类与接口变更、命名更新及迁移建议。

## 1. 模块变更

### 1.1 移除模块

* **Chain 模块**：已合并到 `Tinyflow`，请迁移相关流程逻辑到 Tinyflow。
* **documentParser 模块**：不再提供，请使用 `file2text` 相关功能。
* **DocumentLoader 模块**：不再提供，建议使用 `file2text` 相关功能。

### 1.2 新增模块

* **file2text**：用于文件内容解析与文本抽取，替代旧的 documentParser / DocumentLoader 功能。
* **observability**：提供系统、模型和工具调用的可观测能力。
* **RAG**：支持由 AI 自动将文档拆分为语义片段，便于知识检索和上下文管理。



## 2. 核心类与接口更新

### 2.1 LLM 相关

| v1 旧名             | v2 新名                | 说明         |
| -- | -- |------------|
| LLM               | ChatModel            | 核心大模型接口更名  |
| LLMConfig         | ChatConfig           | 配置类更名      |
| LLMClient         | StreamClient         | 客户端实现接口更名  |
| LLMClientListener | StreamClientListener | 客户端监听器更名   |


### 2.2 函数/工具相关

| v1 旧名    | v2 新名 | 说明                |
| -- | -- |-------------------|
| Function | Tool  | 函数调用逻辑更名为工具，统一调用接口 |



## 3. Prompt 与消息系统更新

### 3.1 Prompt 类更名

| v1 旧名              | v2 新名          | 说明               |
| -- | -- | -- |
| TextPrompt         | SimplePrompt   | 简单文本提示           |
| HistoriesPrompt    | MemoryPrompt   | 历史提示模板，更符合记忆管理语义 |
| TextPromptTemplate | PromptTemplate | 文本模板更名           |
| PromptFormat       | MessageFormat  | 消息格式定义更名         |

### 3.2 消息类合并

| v1 旧名                   | v2 新名       | 说明                              |
| -- | -- |---------------------------------|
| HumanMessage | UserMessage | 所有用户消息统一为 UserMessage，简化模型交互逻辑  |



## 4. 升级迁移建议

1. **流程迁移**

    * 将原 Chain 模块流程迁移到 Tinyflow，调整节点调用逻辑。
2. **文档处理**

    * 使用 `file2text` 替代 documentParser 和 DocumentLoader，统一文件解析流程。
3. **模型调用**（只有在创建自己的模型实现时用到）

    * 将原 `LLMClient` 替换为 `StreamClient`，并更新监听器为 `StreamClientListener`。
    * 更新配置类为 `ChatConfig`，确保原有模型参数兼容。
4. **工具调用**

    * 将 Function 调用替换为 Tool 调用接口。
5. **Prompt 与消息**

    * 更新 Prompt 类与消息类名称，修改相关实例化代码。
    * 将 `HistoriesPrompt` 替换为 `MemoryPrompt`，迁移历史消息逻辑。
    * 将 HumanMessage 改为 UserMessage，检查上下文管理逻辑。
6. **RAG 与文档拆分**

    * 对需要知识检索或上下文拆分的文档，使用 RAG 模块自动拆分，提高下游模型检索效率。




## 5. 注意事项

* 所有 v1 相关模块均需对应替换或迁移，否则可能出现编译错误。
* 新增模块 `file2text`、`observability`、`RAG` 提供更强大的功能，建议根据业务需求逐步接入。
* 升级过程中，注意消息类与 Prompt 类名称变化，保证上下文逻辑一致。
* 可能有些相同的类名，发生了包的变化，请自行检查。


</div>
