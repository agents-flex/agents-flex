/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.rerank;


import com.agentsflex.core.model.config.BaseModelConfig;

public class DefaultRerankModelConfig extends BaseModelConfig {

    private String resultsJsonPath = "$.results";
    private String indexJsonKey = "index";
    private String scoreJsonKey = "relevance_score";


    public String getResultsJsonPath() {
        return resultsJsonPath;
    }

    public void setResultsJsonPath(String resultsJsonPath) {
        this.resultsJsonPath = resultsJsonPath;
    }

    public String getIndexJsonKey() {
        return indexJsonKey;
    }

    public void setIndexJsonKey(String indexJsonKey) {
        this.indexJsonKey = indexJsonKey;
    }

    public String getScoreJsonKey() {
        return scoreJsonKey;
    }

    public void setScoreJsonKey(String scoreJsonKey) {
        this.scoreJsonKey = scoreJsonKey;
    }
}
