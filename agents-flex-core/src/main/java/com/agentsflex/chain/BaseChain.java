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

import com.agentsflex.chain.result.SingleNodeResult;

public abstract class BaseChain<Input, Output> extends Chain<Input, Output> implements ChainNode {

    protected boolean skip;

    @Override
    public boolean isSkip() {
        return skip;
    }

    public void skip() {
        this.skip = true;
    }

    @Override
    public NodeResult<?> execute(NodeResult<?> prevResult, Chain<?, ?> chain) {
        //noinspection unchecked
        Object result = execute((Input) prevResult.getValue());
        return new SingleNodeResult(result);
    }
}