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

import java.util.Base64;

/**
 * 将文档图片发布为 Base64 编码的 Data URI。
 *
 * <p>该实现无状态且线程安全，不依赖外部文件服务，适合作为本地开发、小文档预览和默认兜底策略。
 * 生成的 URI 会直接包含完整图片内容，因此会明显增大 Markdown 字符串和后续模型上下文；生产环境处理
 * 大图片或大量图片时，通常应改用对象存储实现的 {@link DocumentImagePublisher}。</p>
 */
public class DataUriDocumentImagePublisher implements DocumentImagePublisher {

    /**
     * 使用传入 MIME 类型和图片字节生成 {@code data:<mimeType>;base64,<content>} 格式的 URI。
     *
     * <p>{@code fileName} 仅为统一接口提供，该实现生成 Data URI 时不使用文件名。</p>
     *
     * @param imageBytes 图片二进制内容
     * @param mimeType   写入 Data URI 的 MIME 类型
     * @param fileName   原始图片文件名；本实现忽略该参数
     * @return 包含完整图片内容的 Base64 Data URI
     */
    @Override
    public String publish(byte[] imageBytes, String mimeType, String fileName) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
    }
}
