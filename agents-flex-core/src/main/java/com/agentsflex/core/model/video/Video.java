/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.util.IOUtil;
import com.agentsflex.core.util.StringUtil;

import java.io.File;

/**
 * 视频资源。
 * <p>
 * 视频既可以由远程 URL 表示，也可以直接保存为内存字节。视频生成接口通常先返回一个
 * 有有效期的下载 URL，调用 {@link #readBytes()} 或 {@link #writeToFile(File)} 时会在需要时
 * 下载远程内容。
 */
public class Video {
    /**
     * 视频文件的远程访问地址。
     * <p>
     * 服务商返回的地址可能是临时签名 URL，业务侧如需长期保存，应及时下载并转存。
     */
    private String url;

    /**
     * 视频文件的二进制内容。
     * <p>
     * 当该字段有值时，{@link #readBytes()} 优先返回该字段，不再访问 {@link #url}。
     */
    private byte[] bytes;

    /**
     * 视频 MIME 类型，例如 {@code video/mp4}、{@code video/webm}。
     */
    private String mimeType;

    /**
     * 视频封面或代表帧的远程地址，可能为空。
     */
    private String coverUrl;

    /**
     * 视频时长，单位为秒。服务商未返回时为 {@code null}。
     */
    private Integer duration;

    /**
     * 视频画面宽度，单位为像素。服务商未返回时为 {@code null}。
     */
    private Integer width;

    /**
     * 视频画面高度，单位为像素。服务商未返回时为 {@code null}。
     */
    private Integer height;

    /**
     * 使用远程地址创建视频资源。
     *
     * @param url 视频文件地址
     * @return 视频资源
     */
    public static Video ofUrl(String url) {
        Video video = new Video();
        video.setUrl(url);
        return video;
    }

    /**
     * 使用内存字节创建视频资源。
     *
     * @param bytes 视频文件内容
     * @param mimeType 视频 MIME 类型
     * @return 视频资源
     */
    public static Video ofBytes(byte[] bytes, String mimeType) {
        Video video = new Video();
        video.setBytes(bytes);
        video.setMimeType(mimeType);
        return video;
    }

    /**
     * 读取完整的视频文件内容。
     * <p>
     * 如果已经设置 {@link #bytes}，直接返回内存数据；否则从 {@link #url} 下载。
     * 两者都不存在时返回 {@code null}。
     *
     * @return 视频字节，无法读取时返回 {@code null}
     */
    public byte[] readBytes() {
        if (bytes != null && bytes.length > 0) {
            return bytes;
        }
        return StringUtil.hasText(url) ? new HttpClient().getBytes(url) : null;
    }

    /**
     * 将视频写入本地文件。
     * <p>
     * 目标文件的父目录不存在时会自动创建。视频既没有内存字节也没有可下载 URL 时抛出异常。
     *
     * @param file 目标文件
     * @throws IllegalStateException 无法创建父目录或视频没有可写入的数据
     */
    public void writeToFile(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Can not mkdirs for path: " + parent.getAbsolutePath());
        }
        byte[] data = readBytes();
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Video has no data");
        }
        IOUtil.writeBytes(data, file);
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public byte[] getBytes() { return bytes; }
    public void setBytes(byte[] bytes) { this.bytes = bytes; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    @Override
    public String toString() {
        return "Video{" +
            "url='" + url + '\'' +
            ", mimeType='" + mimeType + '\'' +
            ", coverUrl='" + coverUrl + '\'' +
            ", duration=" + duration +
            ", width=" + width +
            ", height=" + height +
            '}';
    }
}
