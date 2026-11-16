package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表格行，仅保存本行开始的锚点单元格。
 *
 * <p>被上方单元格的 {@code rowspan} 覆盖的位置不会出现在本行列表中，
 * 因此渲染器必须结合每个单元格的逻辑列索引确定其实际位置。</p>
 */
public final class TableRow {
    /**
     * 本行开始的锚点单元格。
     */
    private final List<TableCell> cells = new ArrayList<>();

    /**
     * 追加一个锚点单元格。
     *
     * @param cell 要追加的单元格，传入 {@code null} 时忽略
     * @return 当前表格行实例，便于链式构建
     */
    public TableRow addCell(TableCell cell) {
        if (cell != null) cells.add(cell);
        return this;
    }

    /**
     * 获取本行锚点单元格的只读视图。
     *
     * @return 不可修改的单元格列表
     */
    public List<TableCell> getCells() {
        return Collections.unmodifiableList(cells);
    }
}
