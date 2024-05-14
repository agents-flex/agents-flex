# Memory

In Agents-Flex, memory is divided into two types:

- **ChatMemory**: Used to store conversation content during chat with LLMs.
- **ContextMemory**: Used in the chain of agents, similar to a Key-Value data structure.

Additionally, Agents-Flex provides default implementation classes for ChatMemory and ContextMemory:

- **DefaultChatMemory**: Default implementation for storing conversation content.
- **DefaultContextMemory**: Default implementation for the Key-Value data structure.

By default, These implementations are store data in memory. Users can implement custom ChatMemory and ContextMemory for scenarios requiring message persistence.

