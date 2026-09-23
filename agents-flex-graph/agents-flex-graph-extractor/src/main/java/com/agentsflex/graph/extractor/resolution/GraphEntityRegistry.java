package com.agentsflex.graph.extractor.resolution;

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
     * @param type  Schema 节点类型
     * @param names 当前候选聚类中的名称和别名
     * @return 去重后的匹配记录；没有命中时返回空列表
     */
    List<GraphRegisteredEntity> find(String type, Collection<String> names);

    /**
     * 幂等保存或更新一组实体注册记录。
     *
     * <p>该方法只应在对应 GraphMutation 写入成功后调用。实现遇到同一类型和名称已经指向另一
     * nodeId 时必须失败，不能静默覆盖实体身份。</p>
     */
    void saveAll(Collection<GraphRegisteredEntity> entities);
}
