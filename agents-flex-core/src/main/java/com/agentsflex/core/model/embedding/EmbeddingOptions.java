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
package com.agentsflex.core.model.embedding;

import com.agentsflex.core.util.StringUtil;

public class EmbeddingOptions {
    public static final EmbeddingOptions DEFAULT = new EmbeddingOptions() {
        @Override
        public void setModel(String model) {
            throw new IllegalStateException("Can not set modal to the default instance.");
        }

        @Override
        public void setEncodingFormat(String encodingFormat) {
            throw new IllegalStateException("Can not set modal to the default instance.");
        }
    };

    /**
     * 嵌入模型
     */
    private String model;
    /**
     * 嵌入编码格式，可用通常为float, base64
     */
    private String encodingFormat;

    private Integer dimensions;

    private String user;


    public String getModel() {
        return model;
    }

    public String getModelOrDefault(String defaultModel) {
        return StringUtil.noText(model) ? defaultModel : model;
    }

    public void setModel(String model) {
        this.model = model;
    }


    public String getEncodingFormat() {
        return encodingFormat;
    }

    public String getEncodingFormatOrDefault(String defaultEncodingFormat) {
        return StringUtil.noText(encodingFormat) ? defaultEncodingFormat : encodingFormat;
    }

    public void setEncodingFormat(String encodingFormat) {
        this.encodingFormat = encodingFormat;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    @Override
    public String toString() {
        return "EmbeddingOptions{" +
            "model='" + model + '\'' +
            ", encodingFormat='" + encodingFormat + '\'' +
            ", dimensions=" + dimensions +
            ", user='" + user + '\'' +
            '}';
    }
}
