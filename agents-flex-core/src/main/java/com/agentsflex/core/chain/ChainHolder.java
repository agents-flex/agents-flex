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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ChainHolder implements Serializable {

    private String id;
    private String name;
    private String description;

    private ChainHolder parent;
    private List<ChainHolder> children;

    private List<ChainNode> nodes;
    private List<ChainEdge> edges;

    private Map<String, Object> executeResult;

    private Map<String, NodeContext> nodeContexts;

    protected Map<String,ChainNode> suspendNodes=new ConcurrentHashMap<>();
    private List<Parameter> suspendForParameters;
    private ChainStatus status;
    private String message;

    public ChainHolder() {
    }


    public static ChainHolder fromChain(Chain chain) {
        ChainHolder holder = new ChainHolder();
        holder.id = chain.getId();
        holder.name = chain.getName();
        holder.description = chain.getDescription();

        holder.parent = chain.getParent() == null ? null : fromChain(chain.getParent());
        holder.children = chain.getChildren() == null ? null : chain.getChildren().stream().map(ChainHolder::fromChain)
            .collect(Collectors.toList());

        holder.nodes = chain.getNodes();
        holder.edges = chain.getEdges();

        holder.executeResult = chain.getExecuteResult();
        holder.nodeContexts = chain.getNodeContexts();

        holder.suspendNodes = chain.getSuspendNodes();
        holder.suspendForParameters = chain.getSuspendForParameters();
        holder.status = chain.getStatus();

        holder.message = chain.getMessage();

        return holder;
    }


    public static ChainHolder fromJSON(String jsonString) {
        return JSON.parseObject(jsonString, ChainHolder.class, Feature.SupportAutoType);
    }

    public String toJSON() {
        return JSON.toJSONString(this, SerializerFeature.WriteClassName);
    }

    public Chain toChain() {
        return new Chain(this);
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ChainHolder getParent() {
        return parent;
    }

    public void setParent(ChainHolder parent) {
        this.parent = parent;
    }

    public List<ChainHolder> getChildren() {
        return children;
    }

    public void setChildren(List<ChainHolder> children) {
        this.children = children;
    }

    public List<ChainNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ChainNode> nodes) {
        this.nodes = nodes;
    }

    public List<ChainEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<ChainEdge> edges) {
        this.edges = edges;
    }

    public Map<String, Object> getExecuteResult() {
        return executeResult;
    }

    public void setExecuteResult(Map<String, Object> executeResult) {
        this.executeResult = executeResult;
    }

    public Map<String, NodeContext> getNodeContexts() {
        return nodeContexts;
    }

    public void setNodeContexts(Map<String, NodeContext> nodeContexts) {
        this.nodeContexts = nodeContexts;
    }

    public Map<String, ChainNode> getSuspendNodes() {
        return suspendNodes;
    }

    public void setSuspendNodes(Map<String, ChainNode> suspendNodes) {
        this.suspendNodes = suspendNodes;
    }

    public List<Parameter> getSuspendForParameters() {
        return suspendForParameters;
    }

    public void setSuspendForParameters(List<Parameter> suspendForParameters) {
        this.suspendForParameters = suspendForParameters;
    }

    public ChainStatus getStatus() {
        return status;
    }

    public void setStatus(ChainStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "ChainHolder{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", parent=" + parent +
            ", children=" + children +
            ", nodes=" + nodes +
            ", edges=" + edges +
            ", executeResult=" + executeResult +
            ", nodeContexts=" + nodeContexts +
            ", suspendNodes=" + suspendNodes +
            ", suspendForParameters=" + suspendForParameters +
            ", status=" + status +
            ", message='" + message + '\'' +
            '}';
    }
}
