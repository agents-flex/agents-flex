package com.agentsflex.graph.extractor.prompt;

import com.agentsflex.graph.extractor.GraphExtractionRequest;

/**
 * 把 GraphSchema、当前文本和消歧上下文转换为模型提示词的扩展点。
 *
 * <p>自定义实现应明确区分可信指令与不可信文档数据，并与对应 GraphCandidateParser 的输出协议
 * 保持一致。提示词约束不是安全边界，最终结果仍必须经过 Schema 校验。</p>
 */
public interface GraphExtractionPromptBuilder {
    /**
     * @param request 包含 Schema、当前文本、消歧上下文和质量选项的单分段请求
     * @return 发送给 ChatModel 的完整提示词
     */
    String build(GraphExtractionRequest request);
}
