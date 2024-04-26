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
package com.agentsflex.chain.node;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainNode;
import com.agentsflex.chain.NodeResult;
import com.agentsflex.chain.event.OnErrorEvent;
import com.agentsflex.chain.event.OnNodeExecuteAfterEvent;
import com.agentsflex.chain.event.OnNodeExecuteBeforeEvent;
import com.agentsflex.chain.result.MultiNodeResult;
import com.agentsflex.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public abstract class RouterNode extends BaseNode {

    private MultiMatchStrategy multiMatchStrategy = MultiMatchStrategy.ALL;
    private List<ChainNode> nodes;

    public RouterNode(List<ChainNode> nodes) {
        this.nodes = nodes;
    }

    @Override
    public NodeResult<?> execute(NodeResult<?> prevResult, Chain<?, ?> chain) {
        String result = route(prevResult, chain);
        if (StringUtil.noText(result)) {
            return null;
        }

        List<ChainNode> matchNodes = new ArrayList<>();
        String[] ids = result.split(",");
        for (String id : ids) {
            for (ChainNode node : this.nodes) {
                if (Objects.equals(id, String.valueOf(node.getId()))) {
                    matchNodes.add(node);
                }
            }
        }
        if (matchNodes.isEmpty()) {
            return null;
        }

        if (matchNodes.size() == 1) {
            return chain.runNode(matchNodes.get(0));
        }

        return onMatchMultiNodes(matchNodes, chain);
    }


    protected NodeResult<?> onMatchMultiNodes(List<ChainNode> nodes, Chain<?, ?> chain) {
        switch (this.multiMatchStrategy) {
            case FIRST:
                return chain.runNode(nodes.get(0));
            case LAST:
                return chain.runNode(nodes.get(nodes.size() - 1));
            case RANDOM:
                return chain.runNode(nodes.get(ThreadLocalRandom.current().nextInt(nodes.size())));
            case ALL:
                return buildMultiResult(nodes, chain);
            default:
                return null;
        }
    }

    private NodeResult<?> buildMultiResult(List<ChainNode> nodes, Chain<?, ?> chain) {
        List<NodeResult<?>> allResult = new ArrayList<>();
        for (ChainNode node : nodes) {
            try {
                chain.notify(new OnNodeExecuteBeforeEvent(node, chain.getLastResult()));
                if (node.isSkip()) {
                    continue;
                }
                NodeResult<?> nodeResult = node.execute(chain.getLastResult(), chain);
                if (!node.isSkip()) {
                    allResult.add(nodeResult);
                }
            } catch (Exception e) {
                chain.notify(new OnErrorEvent(e));
            } finally {
                chain.notify(new OnNodeExecuteAfterEvent(node, chain.getLastResult()));
            }
        }
        return MultiNodeResult.ofResults(allResult);
    }


    protected abstract String route(Object prevResult, Chain<?, ?> chain);

    public MultiMatchStrategy getMultiMatchStrategy() {
        return multiMatchStrategy;
    }

    public void setMultiMatchStrategy(MultiMatchStrategy multiMatchStrategy) {
        this.multiMatchStrategy = multiMatchStrategy;
    }

    public List<ChainNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ChainNode> nodes) {
        this.nodes = nodes;
    }

    public enum MultiMatchStrategy {
        FIRST,
        LAST,
        RANDOM,
        ALL;
    }

}
