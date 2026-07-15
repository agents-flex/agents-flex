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

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.util.IOUtil;
import com.agentsflex.core.util.ImageUtil;
import com.agentsflex.core.util.StringUtil;

import java.io.File;
import java.util.Base64;

/**
 * 图片数据载体。
 * <p>
 * 图片可以由远程 URL、原始字节或 API 返回的 Base64 字符串表示。通常只需设置其中一种；
 * 若同时存在多种表示，转换和落盘方法会按照各自文档声明的优先级选择。
 */
public class Image {


    /**
     * API {@code b64_json} 字段返回的纯 Base64 图片内容，不包含 Data URI 前缀。
     */
    private String b64Json;

    /**
     * 可访问的远程图片 URL。
     */
    private String url;

    /**
     * 图片文件的原始二进制内容。
     */
    private byte[] bytes;

    /**
     * 图片 MIME 类型，例如 {@code image/png} 或 {@code image/jpeg}。
     * <p>将字节或 Base64 转换为 Data URI 时会使用该值。</p>
     */
    private String mimeType;

    /**
     * 创建 URL 形式的图片对象。
     *
     * @param url 远程图片地址
     * @return 图片对象
     */
    public static Image ofUrl(String url) {
        Image image = new Image();
        image.setUrl(url);
        return image;
    }

    /**
     * 创建字节形式的图片对象。
     *
     * @param bytes    图片原始字节
     * @param mimeType 图片 MIME 类型
     * @return 图片对象
     */
    public static Image ofBytes(byte[] bytes, String mimeType) {
        Image image = new Image();
        image.setBytes(bytes);
        image.mimeType = mimeType;
        return image;
    }

    public String getB64Json() {
        return b64Json;
    }

    public void setB64Json(String b64Json) {
        this.b64Json = b64Json;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public String getMimeType() { return mimeType; }

    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    /**
     * 直接读取当前保存的原始字节。
     * <p>该方法不会下载 URL，也不会解码 {@link #b64Json}。</p>
     *
     * @return 原始字节；未设置时返回 {@code null}
     */
    public byte[] readBytes() {
        return bytes;
    }

    /**
     * 获取适合放入 JSON 请求的图片字符串。
     * <p>优先返回 URL；没有 URL 时，将原始字节或 Base64 内容转换为 Data URI。
     * 三种表示均不存在时返回 {@code null}。</p>
     *
     * @return URL、Data URI 或 {@code null}
     * @throws IllegalArgumentException 当 {@code b64Json} 不是合法 Base64 时抛出
     */
    public String getUrlOrBase64() {
        if (StringUtil.hasText(this.url)) {
            return this.url;
        }

        if (this.bytes != null && this.bytes.length > 0) {
            return ImageUtil.imageBytesToDataUri(bytes, mimeType);
        }

        if (StringUtil.hasText(this.b64Json)) {
            byte[] bytes = Base64.getDecoder().decode(b64Json);
            return ImageUtil.imageBytesToDataUri(bytes, mimeType);
        }

        return null;
    }


    /**
     * 将图片写入本地文件。
     * <p>数据选择顺序为原始字节、Base64、远程 URL。目标父目录不存在时会尝试创建；
     * URL 形式会在调用时发起网络下载。</p>
     *
     * @param file 目标文件
     * @throws IllegalStateException 无法创建目标目录时抛出
     * @throws IllegalArgumentException Base64 内容非法时抛出
     */
    public void writeToFile(File file) {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("Can not mkdirs for path: " + file.getParentFile().getAbsolutePath());
        }
        if (this.bytes != null && this.bytes.length > 0) {
            IOUtil.writeBytes(this.bytes, file);
        } else if (this.b64Json != null) {
            byte[] bytes = Base64.getDecoder().decode(b64Json);
            IOUtil.writeBytes(bytes, file);
        } else if (StringUtil.hasText(this.url)) {
            byte[] bytes = new HttpClient().getBytes(this.url);
            IOUtil.writeBytes(bytes, file);
        }
    }

    @Override
    public String toString() {
        return "Image{" +
            "b64Json='" + b64Json + '\'' +
            ", url='" + url + '\'' +
//            ", bytes=" + Arrays.toString(bytes) +
            ", mimeType='" + mimeType + '\'' +
            '}';
    }
}
