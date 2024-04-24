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
import com.agentsflex.chain.events.OnInvokeAfter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 并发执行，并行执行
 *
 * @param <Input>
 * @param <Output>
 */
public abstract class ParallelChain<Input, Output> extends BaseChain<Input, Output> {

    public ParallelChain() {
    }

    public ParallelChain(Agent<?>... agents) {
        List<Invoker> invokers = new ArrayList<>(agents.length);
        for (Agent<?> agent : agents) {
            invokers.add(new AgentInvoker(agent));
        }
        setInvokers(invokers);
    }

    public ParallelChain(Invoker... invokers) {
        setInvokers(new ArrayList<>(Arrays.asList(invokers)));
    }


    /**
     * 在并发执行下，每个执行 Invoker 都会有自己的结果
     * 需要重写 buildOutput 方法用于对对结果的整理
     */
    @Override
    protected void doExecuteAndSetOutput() {
        List<Object> allResult = new ArrayList<>();
        for (Invoker invoker : invokers) {
            if (invoker.checkCondition(lastResult, this)) {
                Object result = invoker.invoke(lastResult, this);
                allResult.add(result);
                notify(new OnInvokeAfter(this, invoker, result));
            }
        }
        this.output = buildOutput(allResult);
    }

    protected abstract Output buildOutput(List<Object> results);


}
