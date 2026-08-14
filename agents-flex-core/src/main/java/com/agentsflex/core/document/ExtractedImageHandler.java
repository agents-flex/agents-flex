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
package com.agentsflex.core.document;

import java.io.IOException;

/**
 * 文档解析过程中提取出的图片处理器。
 *
 * <p>文档提取器和 OCR 模型共用该扩展点。实现可以把图片上传到对象存储、写入文件
 * 服务或转换为 Data URI，并返回最终写入 Markdown 的图片地址。</p>
 */
@FunctionalInterface
public interface ExtractedImageHandler {

    /**
     * @param imageBytes 图片二进制内容
     * @param mimeType   图片 MIME 类型，无法识别时为 application/octet-stream
     * @param fileName   原始图片文件名
     * @return 用于 Markdown 的 URL 或 Data URI；返回空值时移除图片引用
     */
    String handle(byte[] imageBytes, String mimeType, String fileName) throws IOException;
}
