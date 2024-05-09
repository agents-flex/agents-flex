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

    protected Map<String, Object> metadatas;

    public Object getMetadata(String key) {
        return metadatas != null ? metadatas.get(key) : null;
    }

    public void addMetadata(String key, Object value) {
        if (metadatas == null) {
            metadatas = new HashMap<>();
        }
        metadatas.put(key, value);
    }

    public void addMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (metadatas == null) {
            metadatas = new HashMap<>();
        }
        metadatas.putAll(metadata);
    }

    public Object removeMetadata(String key) {
        if (this.metadatas == null) {
            return null;
        }
        return this.metadatas.remove(key);
    }

    public Map<String, Object> getMetadatas() {
        return metadatas;
    }

    public void setMetadatas(Map<String, Object> metadatas) {
        this.metadatas = metadatas;
    }
}
