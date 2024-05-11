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
import com.agentsflex.chain.ChainNode;
import com.agentsflex.util.StringUtil;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public abstract class RouterNode extends AbstractBaseNode {
    private MultiMatchStrategy multiMatchStrategy = MultiMatchStrategy.ALL;
    private List<ChainNode> nodes;

    public RouterNode() {
    }

    public RouterNode(List<ChainNode> nodes) {
        this.nodes = nodes;
    }

    @Override
    public Map<String, Object> execute(Chain chain) {
        String routeKeys = route(chain);
        if (StringUtil.noText(routeKeys)) {
            return null;
        }

        List<ChainNode> matchNodes = new ArrayList<>();
        String[] ids = routeKeys.split(",");
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
            return executeNode(chain, matchNodes.get(0));
        }

        return onMatchMultiNodes(matchNodes,chain);
    }

    protected Map<String,Object> onMatchMultiNodes(List<ChainNode> nodes, Chain chain) {
        switch (this.multiMatchStrategy) {
            case ALL:
                return buildMultiResult(nodes, chain);
            case FIRST:
                return executeNode(chain,nodes.get(0));
            case LAST:
                return executeNode(chain,nodes.get(nodes.size() - 1));
            case RANDOM:
                return executeNode(chain,nodes.get(ThreadLocalRandom.current().nextInt(nodes.size())));
            default:
                return null;
        }
    }


    private Map<String,Object> buildMultiResult(List<ChainNode> nodes, Chain chain) {
        Map<String,Object> results = new HashMap<>();
        for (ChainNode matchNode : nodes) {
            Map<String, Object> result = executeNode(chain, matchNode);
            if (result != null){
                results.putAll(result);
            }
        }
        return results;
    }

    private Map<String, Object> executeNode(Chain chain, ChainNode node) {
        return node.execute(chain);
    }

    protected abstract String route(Chain chain);

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

    public void addNode(ChainNode node){
        if (nodes == null){
            nodes = new ArrayList<>();
        }
        nodes.add(node);
    }


    public enum MultiMatchStrategy {
        FIRST,
        LAST,
        RANDOM,
        ALL;
    }

    @Override
    public String toString() {
        return "RouterNode{" +
            "multiMatchStrategy=" + multiMatchStrategy +
            ", nodes=" + nodes +
            ", id=" + id +
            ", skip=" + skip +
            '}';
    }
}
