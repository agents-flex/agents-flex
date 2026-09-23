package com.agentsflex.graph.extractor;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.graph.extractor.parser.GraphCandidateParser;
import com.agentsflex.graph.extractor.parser.JsonGraphCandidateParser;
import com.agentsflex.graph.extractor.prompt.DefaultGraphExtractionPromptBuilder;
import com.agentsflex.graph.extractor.prompt.GraphExtractionPromptBuilder;

/**
 * 使用 Agents-Flex ChatModel 完成 Schema 引导的局部知识抽取。
 *
 * <p>该类不保存单次请求状态，可以作为应用单例复用。提示词构造器、响应解析器以及底层
 * ChatModel 也应支持并发调用。运行时可以替换 ChatOptions 模板，字段使用 volatile 保证其他
 * 请求线程能够看到最新引用，但生产应用仍建议在启动阶段完成配置，避免同一批任务参数漂移。</p>
 */
public final class LlmGraphExtractor implements GraphExtractor {
    /**
     * 执行同步结构化抽取的聊天模型。
     */
    private final ChatModel chatModel;
    /**
     * 提示词构造策略。
     */
    private final GraphExtractionPromptBuilder promptBuilder;
    /**
     * 模型响应解析策略。
     */
    private final GraphCandidateParser parser;
    /**
     * 每次调用使用的模型选项模板。
     *
     * <p>volatile 只保证模板引用的发布可见性；调用方不应在发布后继续修改 ChatOptions 对象。</p>
     */
    private volatile ChatOptions chatOptions = new ChatOptions.Builder().temperature(0.1F).build();

    /**
     * 使用默认提示词和 JSON 解析器创建抽取器。
     *
     * @param chatModel 执行同步模型调用的 ChatModel
     */
    public LlmGraphExtractor(ChatModel chatModel) {
        this(chatModel, new DefaultGraphExtractionPromptBuilder(), new JsonGraphCandidateParser());
    }

    /**
     * 使用自定义提示词和解析策略创建抽取器。
     *
     * @param chatModel     执行同步模型调用的 ChatModel
     * @param promptBuilder 把抽取请求转换为模型提示词的策略
     * @param parser        把模型响应转换为候选批次的策略
     */
    public LlmGraphExtractor(ChatModel chatModel, GraphExtractionPromptBuilder promptBuilder,
                             GraphCandidateParser parser) {
        if (chatModel == null) throw new IllegalArgumentException("chatModel must not be null");
        if (promptBuilder == null) throw new IllegalArgumentException("promptBuilder must not be null");
        if (parser == null) throw new IllegalArgumentException("parser must not be null");
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
    }

    /**
     * 原子替换后续模型调用使用的参数模板。
     *
     * <p>已经开始的调用继续使用其进入方法时读取的旧模板，后续调用使用新模板。建议把模板
     * 看作不可变配置，并在应用启动或受控配置切换阶段设置，不要在每个业务请求中来回修改。</p>
     *
     * @param chatOptions 非空模型参数模板
     * @return 当前抽取器，便于链式配置
     */
    public LlmGraphExtractor setChatOptions(ChatOptions chatOptions) {
        if (chatOptions == null) throw new IllegalArgumentException("chatOptions must not be null");
        this.chatOptions = chatOptions;
        return this;
    }

    /**
     * 构建提示词、调用模型并解析候选结果。
     *
     * <p>进入方法后先读取一次 ChatOptions 快照，保证单次调用期间使用同一个模板引用。协议相关
     * 异常原样传播，底层模型或扩展点异常则统一包装为 GraphExtractionException。</p>
     *
     * @param request 当前 Chunk 的不可变抽取请求
     * @return 尚未经过全局实体归一和 GraphMutation 映射的局部候选批次
     */
    @Override
    public GraphCandidateBatch extract(GraphExtractionRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        try {
            // 在调用开始时固定配置快照，避免并发 setChatOptions 让同一次调用观察到两套配置。
            ChatOptions options = this.chatOptions;
            String response = chatModel.chat(promptBuilder.build(request), options);
            return parser.parse(response, request);
        } catch (GraphExtractionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 容错流水线只会把当前异常的 message 写入结构化 issue，因此这里同时保留底层错误摘要。
            // cause 仍完整挂载，调用方需要供应商错误码、HTTP 状态等细节时可以继续沿异常链检查。
            String detail = exception.getMessage();
            String message = detail == null || detail.trim().isEmpty()
                ? "Unable to extract graph candidates"
                : "Unable to extract graph candidates: " + detail.trim();
            throw new GraphExtractionException(message, exception);
        }
    }
}
