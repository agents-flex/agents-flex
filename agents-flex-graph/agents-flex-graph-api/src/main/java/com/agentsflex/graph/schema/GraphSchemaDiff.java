package com.agentsflex.graph.schema;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 两个 Schema 定义之间的结构化差异，适合展示在迁移预览页面。
 */
public final class GraphSchemaDiff {
    private final List<String> additions;
    private final List<String> removals;
    private final List<String> changes;

    GraphSchemaDiff(List<String> additions, List<String> removals, List<String> changes) {
        this.additions = immutable(additions);
        this.removals = immutable(removals);
        this.changes = immutable(changes);
    }

    /**
     * @return 新增的节点、边、属性或索引
     */
    public List<String> getAdditions() {
        return additions;
    }

    /**
     * @return 删除的节点、边、属性或索引
     */
    public List<String> getRemovals() {
        return removals;
    }

    /**
     * @return 类型、必填约束或索引定义变化
     */
    public List<String> getChanges() {
        return changes;
    }

    /**
     * @return 是否没有任何差异
     */
    public boolean isEmpty() {
        return additions.isEmpty() && removals.isEmpty() && changes.isEmpty();
    }

    /**
     * @return 是否包含删除项；删除 Schema 通常需要更高权限确认
     */
    public boolean hasDestructiveChanges() {
        return !removals.isEmpty();
    }

    private static List<String> immutable(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
