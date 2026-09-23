package com.agentsflex.graph.extractor;

/**
 * 从一个文本分段中抽取候选实体和关系的核心扩展点。
 *
 * <p>实现可以调用大模型、规则引擎或外部 NLP 服务，但只应返回候选数据，不应直接写入
 * GraphStore。数据库写入必须留给调用方在审核 {@link GraphExtractionResult} 后显式执行。</p>
 */
public interface GraphExtractor {
    /**
     * 抽取单个 Chunk 的局部候选知识。
     *
     * @param request 包含当前文本、Schema、来源和质量选项的单分段请求
     * @return 候选实体、候选关系、结构化问题和原始响应组成的批次
     */
    GraphCandidateBatch extract(GraphExtractionRequest request);
}
