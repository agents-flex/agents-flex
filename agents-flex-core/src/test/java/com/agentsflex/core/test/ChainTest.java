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

import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.Chain;
import com.agentsflex.chain.SequentialChain;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class ChainTest {

    public static void main(String[] args) {

        SimpleAgent1 agent1 = new SimpleAgent1();
        SimpleAgent2 agent2 = new SimpleAgent2();

        Chain chain = new SequentialChain(agent1, agent2);
        chain.registerInputListener((chain1, parameters) -> {
            Parameter parameter = parameters.get(0);
            System.out.println("请输入 " + parameter.getName());

            Scanner scanner = new Scanner(System.in);
            String userInput = scanner.nextLine();

            Map<String, Object> variables = new HashMap<>();
            variables.put(parameter.getName(), userInput);

            chain.resume(variables);
        });

        chain.execute(new HashMap<>());

        for (Map.Entry<String, Object> entry : chain.getMemory().getAll().entrySet()) {
            System.out.println("执行结果" + entry);
        }
    }
}
