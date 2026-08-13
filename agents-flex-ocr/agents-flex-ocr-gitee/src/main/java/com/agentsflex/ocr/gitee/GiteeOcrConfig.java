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
package com.agentsflex.ocr.gitee;

import com.agentsflex.core.model.ocr.BaseOcrConfig;

/**
 * Gitee AI 异步文档解析服务配置。
 */
public class GiteeOcrConfig extends BaseOcrConfig {
    /**
     * 使用 Gitee AI 官方端点和 Unlimited-OCR 默认模型创建配置。
     */
    public GiteeOcrConfig() {
        setProvider("gitee");
        setEndpoint("https://ai.gitee.com/v1");
        setRequestPath("/async/documents/parse");
        setQueryPath("/task/{taskId}");
        setModel(GiteeOcrModels.UNLIMITED_OCR);
    }
}
