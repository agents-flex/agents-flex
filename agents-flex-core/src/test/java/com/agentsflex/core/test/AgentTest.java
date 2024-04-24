/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.SequentialChain;
import org.junit.Test;

public class AgentTest {

    @Test
    public void testAgent01() {

        SimpleAgent1 agent1 = new SimpleAgent1();
        SimpleAgent2 agent2 = new SimpleAgent2();

        Chain<String, String> chain = new SequentialChain<>(agent1, agent2);
        String execute = chain.execute("xxx");

        System.out.println(execute);

    }
}
