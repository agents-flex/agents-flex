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
package com.agentsflex.util;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Metadata implements Serializable {

    protected Map<String, Object> metadataMap;

    public Object getMetadata(String key) {
        return metadataMap != null ? metadataMap.get(key) : null;
    }

    public void addMetadata(String key, Object value) {
        if (metadataMap == null) {
            metadataMap = new HashMap<>();
        }
        metadataMap.put(key, value);
    }

    public void addMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (metadataMap == null) {
            metadataMap = new HashMap<>();
        }
        metadataMap.putAll(metadata);
    }

    public Object removeMetadata(String key) {
        if (this.metadataMap == null) {
            return null;
        }
        return this.metadataMap.remove(key);
    }

    public Map<String, Object> getMetadataMap() {
        return metadataMap;
    }

    public void setMetadataMap(Map<String, Object> metadatas) {
        this.metadataMap = metadatas;
    }
}
