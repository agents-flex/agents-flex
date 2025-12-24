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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NodeContext {
    public Chain chain;
    public ChainNode currentNode;
    public ChainNode prevNode;
    public String fromEdgeId;

    private AtomicInteger triggerCount = new AtomicInteger(0);
    private List<String> triggerEdgeIds = new ArrayList<>();

    private AtomicInteger executeCount = new AtomicInteger(0);
    private List<String> executeEdgeIds = new ArrayList<>();

    public ChainNode getCurrentNode() {
        return currentNode;
    }

    public ChainNode getPrevNode() {
        return prevNode;
    }

    public String getFromEdgeId() {
        return fromEdgeId;
    }

    public int getTriggerCount() {
        return triggerCount.get();
    }

    public List<String> getTriggerEdgeIds() {
        return triggerEdgeIds;
    }

    public int getExecuteCount() {
        return executeCount.get();
    }

    public List<String> getExecuteEdgeIds() {
        return executeEdgeIds;
    }

    public void setCurrentNode(ChainNode currentNode) {
        this.currentNode = currentNode;
    }

    public void setPrevNode(ChainNode prevNode) {
        this.prevNode = prevNode;
    }

    public void setFromEdgeId(String fromEdgeId) {
        this.fromEdgeId = fromEdgeId;
    }

    public void setTriggerCount(AtomicInteger triggerCount) {
        this.triggerCount = triggerCount;
    }

    public void setTriggerEdgeIds(List<String> triggerEdgeIds) {
        this.triggerEdgeIds = triggerEdgeIds;
    }

    public void setExecuteCount(AtomicInteger executeCount) {
        this.executeCount = executeCount;
    }

    public void setExecuteEdgeIds(List<String> executeEdgeIds) {
        this.executeEdgeIds = executeEdgeIds;
    }


    /**
     * 取出有序交集
     * @param lists
     * @return
     */
    private List<String> orderedIntersection(List<List<String>> lists) {
        if (lists == null || lists.isEmpty()) {
            return Collections.emptyList();
        }

        // 使用第一个列表作为基准
        List<String> firstList = lists.get(0);

        // 收集所有其他列表的集合
        List<Set<String>> otherSets = lists.subList(1, lists.size())
            .stream()
            .map(HashSet::new)
            .collect(Collectors.toList());

        // 过滤第一个列表，只保留在所有其他集合中都存在的元素
        return firstList.stream()
            .filter(id -> otherSets.stream().allMatch(set -> set.contains(id)))
            .collect(Collectors.toList());
    }

    /**
     * 获取指定节点的所有父节点ID
     * @param node 目标节点
     * @return 父节点ID列表
     */
    private List<String> getAllParentId(ChainNode node){
        if (node == null || node.getInwardEdges() == null) {
            return Collections.emptyList();
        }

        List<String> allParentIds = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // 递归获取所有父节点ID
        collectParentIds(node, allParentIds, visited);

        return allParentIds;
    }

    /**
     * 递归收集所有父节点ID
     * @param node 当前节点
     * @param allParentIds 收集的所有父节点ID列表
     * @param visited 已访问的节点ID集合，用于防止循环引用
     */
    private void collectParentIds(ChainNode node, List<String> allParentIds, Set<String> visited) {
        if (node == null || node.getInwardEdges() == null) {
            return;
        }

        for (ChainEdge edge : node.getInwardEdges()) {
            String parentId = edge.getSource();

            // 避免重复访问和循环引用
            if (visited.contains(parentId)) {
                continue;
            }

            visited.add(parentId);
            allParentIds.add(parentId);

            // 查找父节点并递归
            if (chain != null && chain.getNodes() != null) {
                ChainNode parentNode = chain.getNodes().stream()
                    .filter(n -> n.getId().equals(parentId))
                    .findFirst()
                    .orElse(null);

                if (parentNode != null) {
                    collectParentIds(parentNode, allParentIds, visited);
                }
            }
        }
    }

    /**
     * 获取分发节点
     * @return 分发节点，如果没有找到则返回null
     */
    private ChainNode getDispatchNode(){
        if (this.currentNode == null || this.currentNode.getInwardEdges() == null) {
            return null;
        }

        List<ChainNode> nodes = chain.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        // 获取当前节点的所有输入边
        List<ChainEdge> inwardEdges = this.currentNode.getInwardEdges();
        if (inwardEdges.isEmpty()) {
            return null;
        }

        // 为每个输入边获取其源节点的所有父节点ID列表
        List<List<String>> allParentIdLists = inwardEdges.stream()
            .map(edge -> {
                // 找到源节点
                ChainNode sourceNode = nodes.stream()
                    .filter(node -> node.getId().equals(edge.getSource()))
                    .findFirst()
                    .orElse(null);

                if (sourceNode == null) {
                    return Collections.<String>emptyList();
                }

                // 获取源节点的所有父节点ID
                return getAllParentId(sourceNode);
            })
            .filter(list -> !list.isEmpty())
            .collect(Collectors.toList());

        // 如果没有有效的父节点列表，返回null
        if (allParentIdLists.isEmpty()) {
            return null;
        }

        // 计算交集，获取公共的父节点ID
        List<String> commonParentIds = orderedIntersection(allParentIdLists);

        // 如果没有交集，返回null
        if (commonParentIds.isEmpty()) {
            return null;
        }

        // 取交集的第一个节点ID，查找对应的节点作为分发节点
        String dispatchNodeId = commonParentIds.get(0);
        return nodes.stream()
            .filter(node -> node.getId().equals(dispatchNodeId))
            .findFirst()
            .orElse(null);
    }

    private int getExcuteChildCount(ChainNode node) {

        return node.getOutwardEdges().stream()
            .map(edge -> {
                if (edge.getCondition() == null) {
                    return true;
                }
                return edge.getCondition().check(chain, edge, chain.getNodeExecuteResult(node.getId()));
            }).filter(b -> b).collect(Collectors.toList()).size();
    }

    public boolean isUpstreamFullyExecuted() {
        List<ChainEdge> inwardEdges = currentNode.getInwardEdges();
        if (inwardEdges == null || inwardEdges.isEmpty()) {
            return true;
        }

        List<String> shouldBeTriggerIds = inwardEdges.stream().map(ChainEdge::getId).collect(Collectors.toList());

        // 获取分发节点
        ChainNode dispatchNode = getDispatchNode();
        // 应该触发的分支个数
        int shouldBeTriggerCount = shouldBeTriggerIds.size();
        // 找到分发节点后，以分发节点判断条件
        if(dispatchNode != null){
            // 真实的触发多少个分支
            shouldBeTriggerCount = getExcuteChildCount(dispatchNode);
        }

        return triggerEdgeIds.size() >= shouldBeTriggerCount
            && shouldBeTriggerIds.parallelStream().allMatch(triggerEdgeIds::contains);
    }

    public void recordTrigger(Chain.ExecuteNode executeNode) {
        this.currentNode = executeNode.currentNode;
        this.prevNode = executeNode.prevNode;
        this.fromEdgeId = executeNode.fromEdgeId;

        triggerCount.incrementAndGet();
        triggerEdgeIds.add(executeNode.fromEdgeId);
    }

    public synchronized void recordExecute(Chain.ExecuteNode executeNode) {
        executeCount.incrementAndGet();
        executeEdgeIds.add(executeNode.fromEdgeId);
    }
}
