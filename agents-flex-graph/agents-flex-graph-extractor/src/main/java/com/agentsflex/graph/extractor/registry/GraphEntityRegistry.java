package com.agentsflex.graph.extractor.registry;

import java.util.Collection;
import java.util.List;

/**
 * 跨抽取批次的持久化实体注册表扩展点。
 *
 * <p>生产实现可以使用业务主数据、关系数据库、搜索服务或目标图数据库。查询必须限定实体类型，
 * 并按 Unicode NFKC、大小写和连续空白等价语义匹配规范名称与别名。</p>
 */
public interface GraphEntityRegistry {
    /**
     * 查找与任一名称或别名匹配的既有实体。
     *
     * @param space 必填的图空间；名称索引与实体身份必须按空间隔离
     * @param type  Schema 节点类型
     * @param names 当前候选聚类中的名称和别名
     * @return 去重后的匹配记录；没有命中时返回空列表
     */
    List<GraphRegisteredEntity> findMatches(String space, String type, Collection<String> names);

    /**
     * 在指定 Space 中幂等保存或合并一组实体注册记录。
     *
     * <p>该方法只应在对应 GraphMutation 写入成功后调用。实现遇到同一类型和名称已经指向另一
     * nodeId 时必须失败，不能静默覆盖实体身份。应合并同一节点的别名和属性，并保持整批操作原子性；
     * 名称唯一约束必须包含 Space 和实体类型。</p>
     *
     * @param space 必填的目标图空间
     * @param entities 待保存的注册记录
     */
    void saveAll(String space, Collection<GraphRegisteredEntity> entities);
}
