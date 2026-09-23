package com.agentsflex.graph.manager;

import com.agentsflex.graph.GraphOptions;

import java.util.List;

/**
 * 图空间生命周期管理契约。
 *
 * <p>该接口只负责空间的创建、查询、列举和删除，不包含 Schema DDL，便于上层服务按最小
 * 权限和最小依赖注入能力。</p>
 */
public interface GraphSpaceManager {
    /**
     * 创建或校验逻辑图空间。
     */
    void createSpace(GraphSpaceDefinition definition, GraphManager.CreateMode mode);

    /**
     * 携带请求上下文创建空间；默认实现保持旧适配器兼容。
     */
    default void createSpace(GraphSpaceDefinition definition, GraphManager.CreateMode mode, GraphOptions options) {
        createSpace(definition, mode);
    }

    /**
     * 返回指定空间是否存在。
     */
    boolean spaceExists(String name);

    /**
     * 返回当前后端可见的空间名称。
     */
    List<String> listSpaces();

    /**
     * 删除逻辑图空间及其数据。
     */
    void dropSpace(String name);

    /**
     * 携带请求上下文删除空间；默认实现保持旧适配器兼容。
     */
    default void dropSpace(String name, GraphOptions options) {
        dropSpace(name);
    }
}
