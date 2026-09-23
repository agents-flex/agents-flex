package com.agentsflex.graph.extractor.parser;

import com.agentsflex.graph.extractor.GraphCandidateBatch;
import com.agentsflex.graph.extractor.GraphExtractionRequest;

/**
 * 将模型原始响应解析为候选知识批次的协议扩展点。
 *
 * <p>厂商支持 JSON Schema、工具调用或其他结构化输出时，可以替换默认 JSON 解析器。实现应
 * 保留 request 中的 Chunk 作用域和来源信息，并把可恢复的局部错误表示成 GraphExtractionIssue。</p>
 */
public interface GraphCandidateParser {
    /**
     * @param response 模型原始响应，可能包含业务敏感文本
     * @param request  对应请求，提供分段作用域和来源信息
     * @return 结构化候选批次；不应返回 {@code null}
     */
    GraphCandidateBatch parse(String response, GraphExtractionRequest request);
}
