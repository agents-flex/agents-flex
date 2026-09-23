package com.agentsflex.graph.extractor.model;

/**
 * 候选关系相对于原文的断言性质。
 *
 * <p>断言类型用于把客观陈述、模型推断和主体观点分开治理。默认质量策略只接收 EXPLICIT，
 * INFERRED 与 OPINION 必须由调用方通过 GraphExtractionOptions 显式开启。</p>
 */
public enum GraphAssertionType {
    /**
     * 原文直接陈述的事实。
     */
    EXPLICIT,
    /**
     * 从上下文推导出的事实。
     */
    INFERRED,
    /**
     * 角色、叙述者或其他主体表达的观点。
     */
    OPINION
}
