package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 带显示内容和目标地址的行内链接。
 */
public final class LinkInline implements InlineContent {
    /**
     * 链接内部的行内内容。
     */
    private final List<InlineContent> content;
    /**
     * 链接目标地址。
     */
    private final String url;
    /**
     * 可选链接标题。
     */
    private final String title;

    /**
     * 使用纯文本标签创建链接。
     *
     * @param text  链接显示文本
     * @param url   目标地址
     * @param title 可选标题
     */
    public LinkInline(String text, String url, String title) {
        this(Collections.<InlineContent>singletonList(new TextInline(text)), url, title);
    }

    /**
     * 使用结构化行内内容创建链接。
     *
     * @param content 链接显示内容
     * @param url     目标地址
     * @param title   可选标题
     */
    public LinkInline(List<InlineContent> content, String url, String title) {
        this.content = content == null ? Collections.<InlineContent>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(content));
        this.url = url == null ? "" : url;
        this.title = title;
    }

    /**
     * @return 不可修改的链接显示内容
     */
    public List<InlineContent> getContent() {
        return content;
    }

    /**
     * @return 非 {@code null} 的链接地址
     */
    public String getUrl() {
        return url;
    }

    /**
     * @return 链接标题，可为 {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPlainText() {
        StringBuilder text = new StringBuilder();
        for (InlineContent inline : content) text.append(inline.getPlainText());
        return text.toString();
    }
}
