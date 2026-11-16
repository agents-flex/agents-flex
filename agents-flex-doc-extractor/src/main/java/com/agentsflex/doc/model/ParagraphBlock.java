package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 由一个或多个行内元素组成的普通段落。
 */
public final class ParagraphBlock implements DocumentBlock {
    /**
     * 按源文档顺序保存的行内内容。
     */
    private final List<InlineContent> content = new ArrayList<>();

    /**
     * 创建空段落，调用方可通过 {@link #addInline(InlineContent)} 追加内容。
     */
    public ParagraphBlock() {
    }

    /**
     * 使用纯文本创建段落。
     *
     * @param text 段落文本
     */
    public ParagraphBlock(String text) {
        addInline(new TextInline(text));
    }

    /**
     * 使用行内内容列表创建段落。
     *
     * @param content 行内内容
     */
    public ParagraphBlock(List<InlineContent> content) {
        if (content != null) for (InlineContent inline : content) addInline(inline);
    }

    /**
     * 追加行内内容。
     *
     * @param inline 行内内容，传入 {@code null} 时忽略
     * @return 当前段落实例
     */
    public ParagraphBlock addInline(InlineContent inline) {
        if (inline != null) content.add(inline);
        return this;
    }

    /**
     * @return 不可修改的行内内容列表
     */
    public List<InlineContent> getContent() {
        return Collections.unmodifiableList(content);
    }

    /**
     * @return 不含任何可见内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return getPlainText().isEmpty() && content.isEmpty();
    }

    /**
     * @return 拼接后的纯文本表示
     */
    public String getPlainText() {
        StringBuilder text = new StringBuilder();
        for (InlineContent inline : content) text.append(inline.getPlainText());
        return text.toString();
    }
}
