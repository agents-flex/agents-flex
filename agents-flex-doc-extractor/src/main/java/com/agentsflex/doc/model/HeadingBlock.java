package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 具有一至六级语义的标题块。
 */
public final class HeadingBlock implements DocumentBlock {
    /**
     * 标题级别。
     */
    private final int level;
    /**
     * 标题内的行内内容。
     */
    private final List<InlineContent> content;

    /**
     * 使用纯文本创建标题。
     *
     * @param text  标题文本
     * @param level 标题级别，自动限制为 1 至 6
     */
    public HeadingBlock(String text, int level) {
        this(Collections.<InlineContent>singletonList(new TextInline(text)), level);
    }

    /**
     * 使用结构化行内内容创建标题。
     *
     * @param content 标题内容
     * @param level   标题级别，自动限制为 1 至 6
     */
    public HeadingBlock(List<InlineContent> content, int level) {
        this.level = Math.max(1, Math.min(6, level));
        this.content = content == null ? Collections.<InlineContent>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(content));
    }

    /**
     * @return 1 至 6 的标题级别
     */
    public int getLevel() {
        return level;
    }

    /**
     * @return 不可修改的标题行内内容
     */
    public List<InlineContent> getContent() {
        return content;
    }

    /**
     * @return 标题纯文本
     */
    public String getPlainText() {
        StringBuilder text = new StringBuilder();
        for (InlineContent inline : content) text.append(inline.getPlainText());
        return text.toString();
    }
}
