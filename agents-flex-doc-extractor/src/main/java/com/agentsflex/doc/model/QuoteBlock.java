package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 引用内容块，可包含段落、列表或表格等任意块级内容。
 */
public final class QuoteBlock implements DocumentBlock {
    /**
     * 引用内部内容。
     */
    private final List<DocumentBlock> content = new ArrayList<>();

    /**
     * 创建空引用块。
     */
    public QuoteBlock() {
    }

    /**
     * 使用纯文本段落创建引用。
     *
     * @param text 引用文本
     */
    public QuoteBlock(String text) {
        addBlock(new ParagraphBlock(text));
    }

    /**
     * 追加引用内部块。
     *
     * @param block 内容块
     * @return 当前引用块
     */
    public QuoteBlock addBlock(DocumentBlock block) {
        if (block != null) content.add(block);
        return this;
    }

    /**
     * @return 不可修改的引用内容
     */
    public List<DocumentBlock> getContent() {
        return Collections.unmodifiableList(content);
    }
}
