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
package com.agentsflex.core.chain;

import com.agentsflex.core.util.JsConditionUtil;
import com.agentsflex.core.util.Maps;

public class JsCodeCondition implements NodeCondition, EdgeCondition {
    private String code;

    public JsCodeCondition() {
    }

    public JsCodeCondition(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public boolean check(Chain chain, ChainEdge edge) {
        return JsConditionUtil.eval(code, chain, Maps.of("_edge", edge));
    }

    @Override
    public boolean check(Chain chain, NodeContext context) {
        return JsConditionUtil.eval(code, chain, Maps.of("_context", context));
    }
}
