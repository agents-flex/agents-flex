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
package com.agentsflex.chain;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.Parameter;
import com.agentsflex.chain.event.OnNodeExecuteAfterEvent;
import com.agentsflex.chain.event.OnNodeExecuteBeforeEvent;
import com.agentsflex.chain.node.AgentNode;
import com.agentsflex.util.NamedThreadPools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * 并行执行链：并发执行，并行执行（不确定其节点的执行顺序）
 */
public class ParallelChain extends BaseChain {

    private ExecutorService threadPool = NamedThreadPools.newFixedThreadPool("ParallelChain");
    private volatile List<ChainNode> pauseNodes;

    public ParallelChain() {
    }

    public ParallelChain(Agent... agents) {
        List<ChainNode> chainNodes = new ArrayList<>(agents.length);
        for (Agent agent : agents) {
            chainNodes.add(new AgentNode(agent));
        }
        setNodes(chainNodes);
    }

    public ParallelChain(ChainNode... chainNodes) {
        setNodes(new ArrayList<>(Arrays.asList(chainNodes)));
    }


    @Override
    protected void executeInternal() {
        executeNodes(getNodes());
    }

    @Override
    protected void resumeInternal(Map<String, Object> variables) {
        List<ChainNode> nodes = new ArrayList<>(this.pauseNodes);
        this.pauseNodes.clear();
        executeNodes(nodes);
    }


    private void executeNodes(List<ChainNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(nodes.size());
        for (ChainNode node : nodes) {
            threadPool.execute(new ParalleChainRunnable(this, node, latch));
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void waitInput(List<Parameter> parameters, AgentNode agent) {
        if (pauseNodes == null) {
            synchronized (this) {
                if (pauseNodes == null) {
                    pauseNodes = new ArrayList<>();
                }
            }
        }
        pauseNodes.add(agent);
        super.waitInput(parameters, agent);
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    public void setThreadPool(ExecutorService threadPool) {
        this.threadPool = threadPool;
    }

    static class ParalleChainRunnable implements Runnable {
        ParallelChain chain;
        ChainNode node;
        CountDownLatch countDownLatch;

        public ParalleChainRunnable(ParallelChain chain, ChainNode node, CountDownLatch countDownLatch) {
            this.chain = chain;
            this.node = node;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void run() {
            try {
                chain.notifyEvent(new OnNodeExecuteBeforeEvent(node));
                if (chain.getStatus() != ChainStatus.START) {
                    return;
                }

                Map<String, Object> result = node.execute(chain);
                chain.notifyEvent(new OnNodeExecuteAfterEvent(node, result));

                if (chain.getStatus() != ChainStatus.START) {
                    return;
                }

                if (!node.isSkip()) {
                    chain.getMemory().putAll(result);
                }
            } finally {
                this.countDownLatch.countDown();
            }
        }
    }


}
