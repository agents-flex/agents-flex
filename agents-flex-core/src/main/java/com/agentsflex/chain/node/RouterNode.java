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
import com.agentsflex.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class RouterNode extends BaseNode {

    private List<ChainNode> nodes;

    public RouterNode(List<ChainNode> nodes) {
        this.nodes = nodes;
    }

    @Override
    public Object execute(Object prevResult, Chain<?, ?> chain) {
        String result = route(prevResult, chain);
        if (StringUtil.noText(result)) {
            return null;
        }

        List<ChainNode> findNodes = new ArrayList<>();
        String[] ids = result.split(",");
        for (String id : ids) {
            for (ChainNode invoker : this.nodes) {
                if (Objects.equals(id, String.valueOf(invoker.getId()))) {
                    findNodes.add(invoker);
                }
            }
        }

        if (findNodes.isEmpty()) {
            return null;
        }

        if (findNodes.size() == 1) {
            ChainNode node = findNodes.get(0);
            return node.execute(chain.getLastResult(), chain);
        }

        return onMatchMultiNodes(findNodes);
    }

    protected Object onMatchMultiNodes(List<ChainNode> nodes) {
        //todo 支持自定义多匹配策略
        throw new RuntimeException("Can not support match multi nodes");
    }


    protected abstract String route(Object prevResult, Chain<?, ?> chain);

}
