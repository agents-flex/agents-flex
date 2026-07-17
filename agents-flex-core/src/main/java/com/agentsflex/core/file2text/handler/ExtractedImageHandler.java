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
package com.agentsflex.core.file2text.handler;

import java.io.IOException;

/**
 * 文档内嵌图片处理器。
 * <p>
 * 文件解析器提取到图片后，会将图片数据交给该处理器。处理器可以将图片上传到
 * 对象存储、写入本地文件或转换为 Data URI，并返回用于 Markdown 渲染的图片地址。
 */
@FunctionalInterface
public interface ExtractedImageHandler {

    /**
     * 处理从文档中提取的图片。
     *
     * @param imageBytes 图片二进制数据
     * @param mimeType 图片 MIME 类型；无法识别时为 {@code application/octet-stream}
     * @param fileName 文档内的图片文件名；原格式不提供文件名时由解析器生成
     * @return 用于 Markdown 渲染的图片地址，可以是 URL 或 Data URI；返回
     *         {@code null} 或空字符串时不输出该图片
     * @throws IOException 图片保存、上传或转换失败
     */
    String handle(byte[] imageBytes, String mimeType, String fileName) throws IOException;
}
