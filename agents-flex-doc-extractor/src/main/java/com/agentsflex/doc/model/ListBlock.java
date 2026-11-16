package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 有序或无序列表块。
 */
public final class ListBlock implements DocumentBlock {
    /**
     * 是否为有序列表。
     */
    private final boolean ordered;
    /**
     * 有序列表起始序号。
     */
    private final int start;
    /**
     * 列表项。
     */
    private final List<ListItem> items = new ArrayList<>();

    /**
     * 创建从 1 开始的列表。
     *
     * @param ordered 是否为有序列表
     */
    public ListBlock(boolean ordered) {
        this(ordered, 1);
    }

    /**
     * 创建列表。
     *
     * @param ordered 是否为有序列表
     * @param start   有序列表起始序号，非正数会规范为 1
     */
    public ListBlock(boolean ordered, int start) {
        this.ordered = ordered;
        this.start = Math.max(1, start);
    }

    /**
     * 追加列表项。
     *
     * @param item 列表项，传入 {@code null} 时忽略
     * @return 当前列表
     */
    public ListBlock addItem(ListItem item) {
        if (item != null) items.add(item);
        return this;
    }

    /**
     * @return 有序列表返回 {@code true}
     */
    public boolean isOrdered() {
        return ordered;
    }

    /**
     * @return 大于等于 1 的起始序号
     */
    public int getStart() {
        return start;
    }

    /**
     * @return 不可修改的列表项
     */
    public List<ListItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * @return 列表无条目时返回 {@code true}
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
