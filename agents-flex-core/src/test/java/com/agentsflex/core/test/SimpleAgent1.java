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
package com.agentsflex.core.test;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.Output;
import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.Chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimpleAgent1 extends Agent {


    @Override
    public List<Parameter> defineInputParameter() {
        List<Parameter> parameters = new ArrayList<>();
        parameters.add(new Parameter("key1", true));
        return parameters;
    }

    @Override
    public Output execute(Map<String, Object> variables, Chain chain) {
        return new Output().set("result1", "SimpleAgent1" + variables.get("key1"));
    }
}
