///*
// *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
// *  <p>
// *  Licensed under the Apache License, Version 2.0 (the "License");
// *  you may not use this file except in compliance with the License.
// *  You may obtain a copy of the License at
// *  <p>
// *  http://www.apache.org/licenses/LICENSE-2.0
// *  <p>
// *  Unless required by applicable law or agreed to in writing, software
// *  distributed under the License is distributed on an "AS IS" BASIS,
// *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// *  See the License for the specific language governing permissions and
// *  limitations under the License.
// */
//package com.agentsflex.core.test;
//
//import com.agentsflex.chain.Chain;
//import com.agentsflex.chain.SequentialChain;
//import com.agentsflex.chain.node.AgentNode;
//import com.agentsflex.util.Maps;
//import org.junit.Test;
//
//public class ChainTest {
//
//    @Test
//    public void testChain01() {
//
//        SimpleAgent1 agent1 = new SimpleAgent1();
//        SimpleAgent2 agent2 = new SimpleAgent2();
//
//        Chain chain = new SequentialChain(agent1, agent2);
//        chain.execute(Maps.of("name", "张三").build());
//
//        System.out.println(chain.getMemory());
//    }
//
//    @Test
//    public void testChain02() {
//
//        SimpleAgent1 agent1 = new SimpleAgent1();
//        SimpleAgent2 agent2 = new SimpleAgent2();
//
//        SequentialChain chain1 = new SequentialChain(agent1, agent2);
//
//
//        SimpleAgent2 agent22 = new SimpleAgent2();
//        Chain chain2 = new SequentialChain(new AgentNode(agent22), chain1);
//
//        String execute = chain2.execute("xxx");
//
//        System.out.println(execute);
//
//    }
//
//
//    @Test
//    public void testChain03() {
//
//        SimpleAgent1 agent1 = new SimpleAgent1();
//        SimpleAgent2 agent2 = new SimpleAgent2();
//
//        SequentialChain chain1 = new SequentialChain(agent1, agent2);
//
//
//        Chain chain2 = new SequentialChain();
//        chain2.addNode(new SimpleAgent2());
//        chain2.addNode(chain1);
//
//        String execute = chain2.execute("xxx");
//
//        System.out.println(execute);
//    }
//}
