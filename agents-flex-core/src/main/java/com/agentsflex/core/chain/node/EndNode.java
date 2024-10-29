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
package com.agentsflex.core.chain.node;

import com.agentsflex.core.chain.Chain;

import java.util.Collections;
import java.util.Map;

public class EndNode extends BaseNode {
    private boolean normal = true;
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isNormal() {
        return normal;
    }

    public void setNormal(boolean normal) {
        this.normal = normal;
    }

    public EndNode() {
        this.name = "end";
    }

    @Override
    public Map<String, Object> execute(Chain chain) {
        if (normal) {
            chain.stopNormal(message);
        } else {
            chain.stopError(message);
        }
        return Collections.emptyMap();
    }

    @Override
    public String toString() {
        return "EndNode{" +
            "normal=" + normal +
            ", message='" + message + '\'' +
            ", description='" + description + '\'' +
            ", inputInputParameters=" + inputInputParameters +
            ", outputKeys=" + outputKeys +
            ", id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", async=" + async +
            ", inwardEdges=" + inwardEdges +
            ", outwardEdges=" + outwardEdges +
            ", condition=" + condition +
            ", memory=" + memory +
            ", nodeStatus=" + nodeStatus +
            '}';
    }
}
