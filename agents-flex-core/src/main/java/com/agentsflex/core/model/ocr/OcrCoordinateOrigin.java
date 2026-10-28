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
 * OCR 页面坐标系的原点位置。
 */
public enum OcrCoordinateOrigin {
    /**
     * 原点位于页面左上角，Y 轴向下增长。
     */
    TOP_LEFT,
    /**
     * 原点位于页面左下角，Y 轴向上增长。
     */
    BOTTOM_LEFT,
    /**
     * 供应商没有声明或适配器无法判断原点。
     */
    UNKNOWN
}
