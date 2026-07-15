/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.model.image;

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图片生成的统一同步响应。
 * <p>成功响应通过 {@link #getImages()} 返回最终图片；失败响应通过错误标记、错误码和错误消息描述原因。
 * 供应商返回的请求 ID、用量和原始响应字段可保存在继承自 {@link Metadata} 的元数据中。</p>
 */
public class ImageResponse extends Metadata {
    /** 最终生成的图片列表，顺序与供应商响应保持一致。 */
    private List<Image> images;

    /** 是否为失败响应。 */
    private boolean error;

    /** 供应商错误码；供应商未返回错误码时可以为空。 */
    private String errorCode;

    /** 适合日志记录或展示给开发者的错误说明。 */
    private String errorMessage;

    /**
     * 创建失败响应。
     *
     * @param errMessage 错误说明
     * @return 已设置失败标记和错误消息的响应
     */
    public static ImageResponse error(String errMessage) {
        ImageResponse imageResponse = new ImageResponse();
        imageResponse.setError(true);
        imageResponse.setErrorMessage(errMessage);
        return imageResponse;
    }

    /**
     * 获取图片列表。
     *
     * @return 图片列表；内部列表未初始化时返回不可变空列表，不返回 {@code null}
     */
    public List<Image> getImages() {
        return images != null ? images : Collections.emptyList();
    }

    public void setImages(List<Image> images) {
        this.images = images;
    }


    /**
     * 将远程图片 URL 追加到响应末尾。
     *
     * @param url 图片地址
     */
    public void addImage(String url) {
        if (this.images == null) {
            this.images = new ArrayList<>();
        }

        this.images.add(Image.ofUrl(url));
    }

    /**
     * 将二进制图片追加到响应末尾。
     *
     * @param bytes    图片原始字节
     * @param mimeType 图片 MIME 类型
     */
    public void addImage(byte[] bytes, String mimeType) {
        if (this.images == null) {
            this.images = new ArrayList<>();
        }

        this.images.add(Image.ofBytes(bytes, mimeType));
    }

    /**
     * 将图片对象追加到响应末尾。
     *
     * @param image 图片对象
     */
    public void addImage(Image image) {
        if (this.images == null) this.images = new ArrayList<>();
        this.images.add(image);
    }

    /**
     * 获取第一张图片，适合只关心单图结果的调用方。
     *
     * @return 第一张图片；没有图片时返回 {@code null}
     */
    public Image getImage() { return images == null || images.isEmpty() ? null : images.get(0); }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    @Override
    public String toString() {
        return "ImageResponse{" +
            "images=" + images +
            ", error=" + error +
            ", errorCode='" + errorCode + '\'' +
            ", errorMessage='" + errorMessage + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
