package com.agentsflex.doc.model;

/**
 * 独占一个块级位置的图片。
 */
public final class ImageBlock implements DocumentBlock {
    /**
     * 图片地址。
     */
    private final String source;
    /**
     * 图片替代文本。
     */
    private final String altText;
    /**
     * 可选图片标题。
     */
    private final String title;

    /**
     * 创建块级图片。
     *
     * @param source  图片地址
     * @param altText 替代文本
     * @param title   可选标题
     */
    public ImageBlock(String source, String altText, String title) {
        this.source = source == null ? "" : source;
        this.altText = altText == null ? "" : altText;
        this.title = title;
    }

    /**
     * @return 非 {@code null} 的图片地址
     */
    public String getSource() {
        return source;
    }

    /**
     * @return 非 {@code null} 的替代文本
     */
    public String getAltText() {
        return altText;
    }

    /**
     * @return 图片标题，可为 {@code null}
     */
    public String getTitle() {
        return title;
    }
}
