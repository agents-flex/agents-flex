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
package com.agentsflex.core.model.ocr;

/**
 * OCR 页面尺寸和边界框使用的坐标单位。
 */
public enum OcrCoordinateUnit {
    /**
     * 渲染页面的像素坐标。
     */
    PIXEL,
    /**
     * PDF point，1 point 等于 1/72 英寸。
     */
    POINT,
    /**
     * 归一化坐标，通常取值范围为 0 到 1。
     */
    NORMALIZED,
    /**
     * 供应商没有声明或适配器无法判断单位。
     */
    UNKNOWN
}
