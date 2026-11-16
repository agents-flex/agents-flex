package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将多个块组织为一个命名章节、页面、幻灯片或工作表。
 */
public final class SectionBlock implements DocumentBlock {
    /**
     * 章节类型。
     */
    private final SectionType type;
    /**
     * 章节名称。
     */
    private final String title;
    /**
     * 章节内容。
     */
    private final List<DocumentBlock> content = new ArrayList<>();

    /**
     * 创建章节。
     *
     * @param type  章节类型，{@code null} 时使用 {@link SectionType#GENERIC}
     * @param title 章节名称
     */
    public SectionBlock(SectionType type, String title) {
        this.type = type == null ? SectionType.GENERIC : type;
        this.title = title == null ? "" : title;
    }

    /**
     * 追加章节内容块。
     *
     * @param block 内容块
     * @return 当前章节
     */
    public SectionBlock addBlock(DocumentBlock block) {
        if (block != null) content.add(block);
        return this;
    }

    /**
     * @return 章节类型
     */
    public SectionType getType() {
        return type;
    }

    /**
     * @return 非 {@code null} 的章节名称
     */
    public String getTitle() {
        return title;
    }

    /**
     * @return 不可修改的章节内容
     */
    public List<DocumentBlock> getContent() {
        return Collections.unmodifiableList(content);
    }

    /**
     * @return 章节无内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }
}
