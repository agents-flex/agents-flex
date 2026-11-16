package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可表达横向和纵向合并的结构化表格。
 *
 * <p>表格由有序的 {@link TableRow} 组成。合并区域只在左上角保存一个锚点单元格，
 * 其余被覆盖的位置不创建 {@link TableCell}。</p>
 */
public final class TableBlock implements DocumentBlock {
    /**
     * 按视觉顺序保存的表格行。
     */
    private final List<TableRow> rows = new ArrayList<>();

    /**
     * 追加一行表格数据。
     *
     * @param row 要追加的表格行，传入 {@code null} 时忽略
     * @return 当前表格实例，便于链式构建
     */
    public TableBlock addRow(TableRow row) {
        if (row != null) rows.add(row);
        return this;
    }

    /**
     * 获取表格行的只读有序视图。
     *
     * @return 不可修改的表格行列表
     */
    public List<TableRow> getRows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * 判断表格是否不包含任何行。
     *
     * @return 没有表格行时返回 {@code true}
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
