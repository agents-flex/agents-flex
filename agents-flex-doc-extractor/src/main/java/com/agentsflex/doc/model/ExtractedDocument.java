package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析器产生、渲染器消费的结构化文档。
 *
 * <p>该对象保存有序的顶层文档块。对外仅暴露不可修改视图，防止渲染过程中
 * 被调用方意外修改；构建阶段通过 {@link #addBlock(DocumentBlock)} 追加内容。</p>
 */
public final class ExtractedDocument {
    /**
     * 按源文档出现顺序保存的文档块。
     */
    private final List<DocumentBlock> blocks = new ArrayList<>();

    /**
     * 追加一个文档块。
     *
     * <p>传入 {@code null} 时忽略该值，便于解析器按条件追加可选内容。</p>
     *
     * @param block 要追加的文档块，可为 {@code null}
     * @return 当前文档实例，便于链式构建
     */
    public ExtractedDocument addBlock(DocumentBlock block) {
        if (block != null) blocks.add(block);
        return this;
    }

    /**
     * 获取文档块的只读有序视图。
     *
     * @return 不可修改的文档块列表
     */
    public List<DocumentBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    /**
     * 判断文档是否不包含任何可渲染块。
     *
     * @return 没有文档块时返回 {@code true}
     */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }
}
