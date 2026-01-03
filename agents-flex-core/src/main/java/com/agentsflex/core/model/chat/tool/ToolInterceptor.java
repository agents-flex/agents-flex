/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.chat.tool;

/**
 * 函数调用拦截器，用于在函数执行前后插入横切逻辑（如日志、监控、权限等）。
 * <p>
 * 通过责任链模式组合多个拦截器，最终由链尾执行实际函数调用。
 */
public interface ToolInterceptor {

    /**
     * 拦截函数调用。
     *
     * @param context 函数调用上下文
     * @param chain   责任链的下一个节点
     * @return 函数调用结果
     * @throws Exception 通常由实际函数或拦截器抛出
     */
    Object intercept(ToolContext context, ToolChain chain) throws Exception;
}
