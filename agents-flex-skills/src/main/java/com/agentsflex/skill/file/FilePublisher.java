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
package com.agentsflex.skill.file;

/**
 * 将 Skill Runtime 生成的文件发布为用户可访问 URL 的扩展接口。
 *
 * <p>实现可以把文件保存到应用静态目录、对象存储、企业文件中心或其他文件服务，
 * 最终返回一个可交付给模型和用户的 URL。</p>
 *
 * <p>实现必须在 {@link #publish(FilePublishRequest)} 返回之前同步消费完请求中的
 * InputStream，不得关闭、缓存或交给异步线程继续读取。输入流由调用方负责关闭。</p>
 */
@FunctionalInterface
public interface FilePublisher {

    /**
     * 发布文件。
     *
     * @param request 包含输入流、文件信息和 Runtime 来源的发布请求
     * @return 已发布文件信息，不能为 {@code null}
     */
    PublishedFile publish(FilePublishRequest request);
}
