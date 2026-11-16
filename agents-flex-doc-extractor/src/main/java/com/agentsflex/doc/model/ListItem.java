package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 列表中的一个条目，可同时包含多个块和一个嵌套子列表。
 */
public final class ListItem {
    /**
     * 列表项的块级内容。
     */
    private final List<DocumentBlock> content = new ArrayList<>();
    /**
     * 可选嵌套子列表。
     */
    private ListBlock children;

    /**
     * 创建空列表项。
     */
    public ListItem() {
    }

    /**
     * 使用一个纯文本段落创建列表项。
     *
     * @param text 列表项文本
     */
    public ListItem(String text) {
        addBlock(new ParagraphBlock(text));
    }

    /**
     * 追加列表项内容块。
     *
     * @param block 内容块，传入 {@code null} 时忽略
     * @return 当前列表项
     */
    public ListItem addBlock(DocumentBlock block) {
        if (block != null) content.add(block);
        return this;
    }

    /**
     * @return 不可修改的列表项内容块
     */
    public List<DocumentBlock> getContent() {
        return Collections.unmodifiableList(content);
    }

    /**
     * @return 嵌套子列表，可为 {@code null}
     */
    public ListBlock getChildren() {
        return children;
    }

    /**
     * 设置嵌套子列表。
     *
     * @param children 嵌套列表
     * @return 当前列表项
     */
    public ListItem setChildren(ListBlock children) {
        this.children = children;
        return this;
    }
}
