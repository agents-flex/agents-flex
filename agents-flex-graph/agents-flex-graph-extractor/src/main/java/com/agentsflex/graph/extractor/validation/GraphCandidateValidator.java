package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionRequest;

/**
 * 根据 GraphSchema 和质量选项筛选候选知识的扩展点。
 *
 * <p>自定义实现可以增加租户词表、业务状态或审核规则，但应保留解析阶段已经产生的问题，并
 * 返回一个只包含可进入实体归一阶段的合法候选子集。</p>
 */
public interface GraphCandidateValidator {
    /**
     * 校验一个分段的候选结果，并返回可进入实体解析阶段的子集。
     *
     * @param candidates 解析阶段生成的局部候选结果
     * @param request 对应 Chunk 请求，提供 Schema、原文和质量选项
     * @return 合法候选子集与完整问题列表
     */
    GraphCandidateValidationResult validate(GraphCandidateResult candidates, GraphExtractionRequest request);
}
