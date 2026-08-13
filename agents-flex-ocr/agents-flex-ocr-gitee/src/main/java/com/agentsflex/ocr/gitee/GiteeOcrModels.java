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

/**
 * Gitee AI 文档解析接口支持的常用模型标识。
 */
public final class GiteeOcrModels {
    /**
     * Gitee 通用 OCR 模型。
     */
    public static final String UNLIMITED_OCR = "Unlimited-OCR";
    /**
     * PDF 文档结构提取模型。
     */
    public static final String PDF_EXTRACT_KIT_1_0 = "PDF-Extract-Kit-1.0";
    /**
     * MinerU 2.5 文档解析模型。
     */
    public static final String MINERU_2_5 = "MinerU2.5";
    /**
     * DeepSeek OCR 模型。
     */
    public static final String DEEPSEEK_OCR = "DeepSeek-OCR";
    /**
     * MinerU 2.5 Pro 文档解析模型。
     */
    public static final String MINERU_2_5_PRO = "MinerU2.5-Pro";
    /**
     * PaddleOCR-VL 1.5 模型。
     */
    public static final String PADDLE_OCR_VL_1_5 = "PaddleOCR-VL-1.5";
    /**
     * PaddleOCR-VL 通用版本标识。
     */
    public static final String PADDLE_OCR_VL = "PaddleOCR-VL";

    /**
     * 常量类不允许实例化。
     */
    private GiteeOcrModels() {
    }
}
