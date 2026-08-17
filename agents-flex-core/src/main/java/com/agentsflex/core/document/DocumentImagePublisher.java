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
 * 文档图片发布扩展点。
 *
 * <p>文档提取器和 OCR 模型通过该接口，把已经取得的图片二进制内容转换为 Markdown
 * 可引用的地址。实现可以将图片上传到对象存储、写入应用文件服务，或者直接生成 Data URI；
 * 框架只负责把返回值写回 Markdown，不关心图片的具体存储方式。</p>
 *
 * <p>同一个发布器实例可能被多个文档解析请求并发调用。实现类应保持线程安全，并避免把请求相关的
 * 可变状态保存在实例字段中。对于可能重复执行的文档解析或重试场景，建议根据图片内容摘要生成稳定的
 * 存储 Key，使发布操作具备幂等性。</p>
 */
@FunctionalInterface
public interface DocumentImagePublisher {

    /**
     * 发布一张文档图片并返回 Markdown 可引用的地址。
     *
     * <p>框架会同步调用该方法。返回 {@code null}、空字符串或纯空白字符串表示不保留该图片，
     * 调用方会移除对应的 Markdown 或 HTML 图片引用。方法不应修改传入的字节数组。</p>
     *
     * @param imageBytes 图片二进制内容；框架调用时为非空、非零长度数组
     * @param mimeType   图片 MIME 类型；无法识别时通常为 {@code application/octet-stream}
     * @param fileName   原始图片文件名；原格式未提供文件名时通常为 {@code embedded-image}
     * @return 可用于 Markdown 的 HTTP/HTTPS URL、Data URI 或其他受支持的图片引用；返回空值时移除图片
     * @throws IOException 图片保存、上传或地址生成失败
     */
    String publish(byte[] imageBytes, String mimeType, String fileName) throws IOException;
}
