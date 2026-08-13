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
package com.agentsflex.ocr.mineru;

/**
 * MinerU 服务支持的模型版本常量。
 */
public final class MineruOcrModels {
    /**
     * MinerU Pipeline 文档解析模式。
     */
    public static final String PIPELINE = "pipeline";
    /**
     * MinerU 视觉语言模型解析模式。
     */
    public static final String VLM = "vlm";
    /**
     * MinerU HTML 结构化解析模型。
     */
    public static final String MINERU_HTML = "MinerU-HTML";

    /**
     * 常量类不允许实例化。
     */
    private MineruOcrModels() {
    }
}
