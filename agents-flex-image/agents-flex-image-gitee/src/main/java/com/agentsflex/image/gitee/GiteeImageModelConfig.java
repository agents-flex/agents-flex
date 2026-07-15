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
package com.agentsflex.image.gitee;

import com.agentsflex.core.model.image.BaseImageConfig;

/**
 * Gitee AI 图片模型配置。
 * <p>默认请求同步文生图接口；携带输入图片时，适配器会改用 {@link #editPath} 指定的编辑接口。</p>
 */
public class GiteeImageModelConfig extends BaseImageConfig {
    /** 同步图片编辑接口路径。 */
    private String editPath = "/v1/images/edits";

    /** 初始化 Gitee AI 默认端点、默认模型和已声明的图片能力。 */
    public GiteeImageModelConfig() {
        setProvider("gitee");
        setEndpoint("https://ai.gitee.com");
        setRequestPath("/v1/images/generations");
        setModel(GiteeImageModels.FLUX_1_SCHNELL);
        setSupportTextToImage(true);
        setSupportImageToImage(true);
        setSupportImageEditing(true);
        setSupportMultipleInputImages(false);
        setSupportMultipleOutputImages(true);
        setSupportMask(true);
        setMaxInputImages(1);
        setMaxOutputImages(4);
    }

    public String getEditPath() {
        return editPath;
    }

    /** 设置编辑接口路径；缺少前导斜杠时会自动补齐。 */
    public void setEditPath(String editPath) {
        if (editPath != null && !editPath.startsWith("/")) editPath = "/" + editPath;
        this.editPath = editPath;
    }

    /** @return 由 endpoint 与 editPath 拼接出的完整图片编辑地址 */
    public String getEditUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (editPath == null ? "" : editPath);
    }
}
