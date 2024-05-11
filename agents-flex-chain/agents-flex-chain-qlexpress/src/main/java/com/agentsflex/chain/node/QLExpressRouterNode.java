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
package com.agentsflex.chain.node;

import com.agentsflex.chain.Chain;
import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;

public class QLExpressRouterNode extends RouterNode {

    private String express;

    @Override
    protected String route(Chain chain) {
        ExpressRunner runner = new ExpressRunner();
        DefaultContext<String, Object> context = new DefaultContext<>();
        context.putAll(chain.getMemory().getAll());
        context.put("chain", chain);
        try {
            Object result = runner.execute(express, context, null, true, false);
            if (result != null) return result.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public String getExpress() {
        return express;
    }

    public void setExpress(String express) {
        this.express = express;
    }
}
