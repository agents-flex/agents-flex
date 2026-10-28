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
package com.agentsflex.ocr.somark;

import com.agentsflex.core.model.ocr.BaseOcrConfig;

/**
 * SoMark 文档智能解析服务配置。
 */
public class SomarkOcrConfig extends BaseOcrConfig {
    /**
     * 同步解析路径。
     */
    private String syncPath;

    /**
     * 使用 SoMark 正式版 API 的新版解析路径创建配置。
     */
    public SomarkOcrConfig() {
        setProvider("somark");
        setEndpoint("https://somark.cn/api/v1");
        setRequestPath("/parse/async");
        setQueryPath("/parse/async_check");
        setSyncPath("/parse/sync");
        setPollIntervalMillis(3_000L);
    }

    /**
     * 返回同步解析路径。
     */
    public String getSyncPath() {
        return syncPath;
    }

    /**
     * 设置同步解析路径；非空路径缺少前导斜杠时会自动补齐。
     */
    public void setSyncPath(String syncPath) {
        this.syncPath = syncPath != null && !syncPath.startsWith("/") ? "/" + syncPath : syncPath;
    }

    /**
     * 返回同步解析完整地址。
     */
    public String getSyncUrl() {
        return getEndpoint() + syncPath;
    }
}
