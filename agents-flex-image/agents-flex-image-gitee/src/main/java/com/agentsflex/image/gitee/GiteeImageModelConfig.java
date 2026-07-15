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

public class GiteeImageModelConfig extends BaseImageConfig {
    private String editPath = "/v1/images/edits";

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

    public void setEditPath(String editPath) {
        if (editPath != null && !editPath.startsWith("/")) editPath = "/" + editPath;
        this.editPath = editPath;
    }

    public String getEditUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (editPath == null ? "" : editPath);
    }
}
