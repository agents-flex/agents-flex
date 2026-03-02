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
package com.agentsflex.text2sql.core;

import java.util.function.Function;

/**
 * SQL 验证器接口（函数式接口，支持 Lambda）
 * <p>
 * 典型用途：
 * <ul>
 *   <li>敏感字段访问拦截（如 password, secret_key）</li>
 *   <li>业务规则校验（如 JOIN 数量限制）</li>
 *   <li>权限校验（如用户是否有权访问某表）</li>
 * </ul>
 *
 * @return null 表示验证通过，否则返回错误信息
 */
@FunctionalInterface
public interface SqlValidator {
    String validate(SqlValidationContext context);

    /**
     * 快捷创建：从 Function 转换
     */
    static SqlValidator of(Function<SqlValidationContext, String> func) {
        return func::apply;
    }
}
