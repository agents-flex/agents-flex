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
import com.agentsflex.core.util.StringUtil;

import java.util.Map;

public abstract class CodeNode extends BaseNode {
    protected String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    protected Map<String, Object> execute(Chain chain) {
        if (StringUtil.noText(code)) {
            throw new IllegalStateException("Code is null or blank.");
        }

        return executeCode(this.code, chain);
    }

    protected abstract Map<String, Object> executeCode(String code, Chain chain);
}
