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
package com.agentsflex.core.chain.dsl;

import com.agentsflex.core.chain.Chain;

import java.util.ArrayList;
import java.util.List;

public class Workspace {
    private List<LlmWrapper> llms;
    private List<Node> nodes;
    private List<Edge> edges;

    private String llmsJsonString;
    private String nodesJsonString;
    private String linesJsonString;

    public List<LlmWrapper> getLlms() {
        return llms;
    }

    public void setLlms(List<LlmWrapper> llms) {
        this.llms = llms;
    }

    public void addLlm(LlmWrapper llmWrapper) {
        if (this.llms == null) {
            this.llms = new ArrayList<>();
        }
        this.llms.add(llmWrapper);
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }

    public void addNode(Node node) {
        if (this.nodes == null) {
            this.nodes = new ArrayList<>();
        }
        this.nodes.add(node);
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void setEdges(List<Edge> edges) {
        this.edges = edges;
    }

    public void addEdge(Edge edge) {
        if (this.edges == null) {
            this.edges = new ArrayList<>();
        }
        this.edges.add(edge);
    }

    public String getLlmsJsonString() {
        return llmsJsonString;
    }

    public void setLlmsJsonString(String llmsJsonString) {
        this.llmsJsonString = llmsJsonString;
    }

    public String getNodesJsonString() {
        return nodesJsonString;
    }

    public void setNodesJsonString(String nodesJsonString) {
        this.nodesJsonString = nodesJsonString;
    }

    public String getLinesJsonString() {
        return linesJsonString;
    }

    public void setLinesJsonString(String linesJsonString) {
        this.linesJsonString = linesJsonString;
    }

    public Chain toChain() {
        return null;
    }

    public String toXml() {
        return null;
    }
}
