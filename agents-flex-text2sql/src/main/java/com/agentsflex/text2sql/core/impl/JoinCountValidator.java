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
package com.agentsflex.text2sql.core.impl;

import com.agentsflex.text2sql.core.SqlValidationContext;
import com.agentsflex.text2sql.core.SqlValidator;
import com.agentsflex.text2sql.core.ValidationResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置：JOIN 数量限制验证器
 */
public class JoinCountValidator implements SqlValidator {
    private final int maxJoinCount;

    public JoinCountValidator(int maxJoinCount) {
        this.maxJoinCount = maxJoinCount;
    }

    @Override
    public ValidationResult validate(SqlValidationContext context) {
        String sql = context.getOriginalSql();
        Pattern pattern = Pattern.compile("\\bJOIN\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
        long joinCount = 0;
        while (matcher.find()) {
            joinCount++;
        }

        if (joinCount > maxJoinCount) {
            return ValidationResult.fail(
                "Too many JOINs (max " + maxJoinCount + ", found " + joinCount + ")",
                "PERF_001",
                "Consider breaking query into multiple steps or using materialized views"
            );
        }
        return ValidationResult.pass();
    }
}
