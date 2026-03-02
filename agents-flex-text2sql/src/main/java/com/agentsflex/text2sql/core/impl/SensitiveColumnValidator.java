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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置：敏感列访问拦截器
 */
public class SensitiveColumnValidator implements SqlValidator {
    private final List<String> forbiddenColumns;

    public SensitiveColumnValidator(String... columns) {
        this.forbiddenColumns = Arrays.asList(columns);
    }

    @Override
    public String validate(SqlValidationContext context) {
        String sql = context.getOriginalSql().toLowerCase();
        for (String col : forbiddenColumns) {
            Pattern pattern = Pattern.compile("\\b" + col.toLowerCase() + "\\b");
            Matcher matcher = pattern.matcher(sql);
            if (matcher.find()) {
                return "Access to sensitive column '" + col + "' is prohibited";
            }
        }
        return null;
    }
}
