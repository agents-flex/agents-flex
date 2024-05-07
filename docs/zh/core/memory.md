# Memory 记忆
在 Agents-Flex 中，Memory 分为两种，分别是：
- ChatMemory：用于存储大语言模型在对话的过程中对话内容。
- ContextMemory：用于在智能体执行链中，类似 Map 的 Key-Value 数据数据结构

同时，在 Agents-Flex 中有对 ChatMemory 以及 ContextMemory 的默认实现类，他们分别是 DefaultChatMemory 以及 DefaultContextMemory。
他们默认存储在内存中，用户可以通过去实现 ChatMemory 以及 ContextMemory，用于消息持久化的场景。

