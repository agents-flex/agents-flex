package com.agentsflex.graph.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可移植的多分支查询组合。
 *
 * <p>每个分支都是独立的 {@link TraversalQuery}，适配器将其编译为目标方言的 UNION。
 * 分支应保持相同的投影列名和语义，SDK 会在构造时校验列结构。</p>
 */
public final class GraphUnionQuery implements GraphQuery {
    /**
     * 按执行顺序排列的遍历分支。
     */
    private final List<TraversalQuery> branches;
    /**
     * 是否保留分支间的重复记录；{@code false} 表示去重 UNION。
     */
    private final boolean all;

    /**
     * 创建并校验不可变分支集合。
     */
    private GraphUnionQuery(List<TraversalQuery> branches, boolean all) {
        this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
        this.all = all;
        validate();
    }

    /**
     * 创建去重 UNION。
     *
     * @param first 第一个查询分支
     * @param rest  其余查询分支，可为空
     * @return 已校验的 UNION 查询
     */
    public static GraphUnionQuery union(TraversalQuery first, TraversalQuery... rest) {
        return create(false, first, rest);
    }

    /**
     * 创建保留重复记录的 UNION ALL。
     *
     * @param first 第一个查询分支
     * @param rest  其余查询分支，可为空
     * @return 已校验的 UNION ALL 查询
     */
    public static GraphUnionQuery unionAll(TraversalQuery first, TraversalQuery... rest) {
        return create(true, first, rest);
    }

    private static GraphUnionQuery create(boolean all, TraversalQuery first, TraversalQuery[] rest) {
        if (first == null) throw new IllegalArgumentException("union first branch must not be null");
        List<TraversalQuery> values = new ArrayList<>();
        values.add(first);
        if (rest != null) {
            for (TraversalQuery branch : rest) {
                if (branch == null) throw new IllegalArgumentException("union branch must not be null");
                values.add(branch);
            }
        }
        return new GraphUnionQuery(values, all);
    }

    /**
     * 校验分支数量、投影列名和投影语义是否兼容。
     */
    @Override
    public void validate() {
        if (branches.isEmpty()) throw new IllegalArgumentException("union requires at least one branch");
        List<TraversalQuery.Projection> expected = branches.get(0).getProjections();
        for (TraversalQuery branch : branches) {
            branch.validate();
            if (branch.getProjections().size() != expected.size()) {
                throw new IllegalArgumentException("union branches must have the same projection count");
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).getOutputName().equals(branch.getProjections().get(i).getOutputName())) {
                    throw new IllegalArgumentException("union branches must have the same projection names");
                }
                TraversalQuery.Projection actual = branch.getProjections().get(i);
                if (expected.get(i).getKind() != actual.getKind()
                    || !same(expected.get(i).getProperty(), actual.getProperty())
                    || expected.get(i).getAggregateFunction() != actual.getAggregateFunction()) {
                    throw new IllegalArgumentException("union branches must have compatible projection semantics");
                }
            }
        }
    }

    private static boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * @return 按执行顺序排列的只读查询分支。
     */
    public List<TraversalQuery> getBranches() {
        return branches;
    }

    /**
     * @return 是否为保留重复记录的 UNION ALL。
     */
    public boolean isAll() {
        return all;
    }
}
