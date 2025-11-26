# Chain 和 工作流变化 / Tinyflow 集成指南
<div v-pre>


## 背景

* 在 Agents-Flex v1 中，曾经使用一个独立的 **Chain 模块** 用于“ AI 工作流 / AI 任务编排 / AI 流程” 的表达和执行。
* 随着产品演进，我们决定将 Chain 模块 **移除**，并合并到开源工具 Tinyflow，它提供了前端的 UI 组件和后端的编排框架代码实现。
* Tinyflow 是一个轻量、灵活、无侵入、前后端通用的 AI 工作流编排解决方案。它适配 Agents-Flex、SpringAI、LangChain4j 等后端，也提供前端组件（Web Component），便于与现有业务系统集成。 ([Gitee][1])
* 对于原有使用 Chain 的项目/代码，需要做一定迁移，主要是包名的修改。


## 1. 概念变化

| 旧 (Agents-Flex v1)                                       | 新 (v2.0.0-beta.1 + Tinyflow)                                                 |
| -- | - |
| Chain —— 独立模块，用于串联多个步骤 / 调用流程 (可能是 LLM 调用、工具调用、条件判断、分支等) | Tinyflow —— 专注 AI 工作流 / 智能体流程编排，支持复杂节点 (LLM、Tool、RAG、条件、分支、并发等)              |
| 内建流程表达 & 执行逻辑                                            | 使用 Tinyflow 的流程模型 + 后端执行器 (tinyflow-java 等)                                  |
| 与 Agents-Flex 功能混杂 (LLM / Embedding / RAG / 自定义工具)       | 将流程与业务解耦，流程交给 Tinyflow 管理，Agents-Flex 保留基础能力 (LLM / Embedding / RAG / 工具调用等) |

通过分离“流程编排能力”与“AI 能力基础能力 (LLM、Vector, RAG 等)”，降低耦合，提高灵活度，也便于在不同系统间重用流程。


## 2. Tinyflow 简介

* Tinyflow 是一个轻量级、灵活且无侵入性的 AI 工作流编排解决方案。它不是一个完整产品，而是一个开发组件，方便嵌入已有业务系统。 ([tinyflow.cn][2])
* 技术特征：

    * **前端**：基于 Web Component，可与 React / Vue / Svelte / 原生 JS 等任意前端集成。 ([GitHub][3])
    * **后端**：提供 Java (tinyflow-java)、Agents-Flex、SpringAI、LangChain4j 不同技术栈。 ([Gitee][1])
    * **执行单元**：Node，可以是 LLM 调用、Tool 调用、RAG 检索、条件判断、并发/分支、Loop、异步等，适合复杂 AI 流程 & 智能体编排场景。 ([tinyflow.cn][2])
    * **高度兼容现有系统**：Tinyflow 强调“无侵入性”，不强制业务重构。 ([tinyflow.cn][2])


## 3. 为什么推荐 Tinyflow 而不是保留 Chain

* **Tinyflow 专注于流程编排**，设计更清晰，而不混杂与模型 / 向量 / RAG 等基础能力。
* **前后端通用 + 灵活**：既能可视化编辑流程 (前端)，也能后端执行 (Java / Node / Python) — 方便跨平台 & 跨语言开发。 ([Gitee][1])
* **更好的扩展性**：随着功能需求增长 (RAG、Observability、多 agent、并发、异步、条件…)，Tinyflow 更容易扩展和演进。
* **解耦业务与 AI 流程**：业务逻辑与 AI 流程分离，有利于长期维护，可插拔、替换、重构。



## 4. 总结

* v2.0.0-beta.1 移除了原有 Chain 模块，并推荐使用 Tinyflow 来承担 AI 工作流 / 编排任务。
* Tinyflow 提供了前端 / 后端 / 多语言支持、清晰的节点 + 流程模型、灵活的扩展能力，非常适合现代 AI 应用中的复杂流程。
* 对已有项目建议进行流程迁移 — 虽然有一定成本，但从长远来看可以获得更清晰、可维护、易扩展的架构体系。
* 我们建议将 Tinyflow 作为 “AI 流程编排引擎 + 执行时基础设施”，将 Agents-Flex 保留为 “AI 能力基础设施 (LLM / Embedding / RAG / Tools / Vector Store)” —— 两者分工明确，相辅相成。



[1]: https://gitee.com/tinyflow-ai/tinyflow "Tinyflow"
[2]: https://tinyflow.cn/zh/what-is-tinyflow.html "Tinyflow 是什么？"
[3]: https://github.com/tinyflow-ai/tinyflow "Tinyflow is a lightweight AI agent solution."
[4]: https://gitee.com/tinyflow-ai/tinyflow-java "Tinyflow-java"



</div>
