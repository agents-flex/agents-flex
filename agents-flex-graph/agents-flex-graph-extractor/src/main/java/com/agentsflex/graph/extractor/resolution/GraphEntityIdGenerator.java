package com.agentsflex.graph.extractor.resolution;

/**
 * 为归一后的实体生成稳定 GraphNode ID 的扩展点。
 *
 * <p>默认实现使用类型和规范名称的哈希。持续维护长期图谱时，业务可以替换本接口，使 ID 与
 * 既有主数据、实体注册中心或数据库主键策略保持一致。</p>
 */
public interface GraphEntityIdGenerator {
    /**
     * @param type          Schema 节点类型
     * @param canonicalName 归一后的主名称
     * @return 稳定且非空的节点 ID
     */
    String generate(String type, String canonicalName);
}
