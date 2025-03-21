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

import com.agentsflex.core.chain.*;
import com.agentsflex.core.chain.node.LlmNode;
import com.agentsflex.core.chain.node.StartNode;
import com.agentsflex.core.util.Maps;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ChainTest {

    public static void main(String[] args) {

        StartNode startNode = new StartNode();
        startNode.setId(UUID.randomUUID().toString());

        LlmNode endNode = new LlmNode();
        endNode.setId(UUID.randomUUID().toString());

        Parameter parameter = new Parameter();
        parameter.setName("test");
        parameter.setDataType(DataType.String);
        parameter.setRequired(true);

        endNode.addInputParameter(parameter);

        Chain chain = new Chain();
        chain.addNode(startNode);
        chain.addNode(endNode);

        ChainEdge edge = new ChainEdge();
        edge.setId(UUID.randomUUID().toString());
        edge.setSource(startNode.getId());
        edge.setTarget(endNode.getId());
        chain.addEdge(edge);


        chain.addEventListener(new ChainEventListener() {
            @Override
            public void onEvent(ChainEvent event, Chain chain) {
                System.out.println("onEvent: " + event.getClass());
            }
        });


        chain.addSuspendListener(new ChainSuspendListener() {

            @Override
            public void onSuspend(Chain chain) {
                System.out.println("------------onSuspend-----------");
                List<Parameter> suspendForParameters = chain.getSuspendForParameters();
                System.out.println("suspendForParameters: " + suspendForParameters);

                chain.resume(Maps.of("test","123"));
            }
        });


        chain.execute(Collections.emptyMap());

    }
}
