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

import java.util.Objects;

/**
 * SQL 验证结果（不可变）
 * <p>
 * 使用静态工厂方法创建，语义清晰：
 * <pre>
 *   ValidationResult.pass()                    // 验证通过
 *   ValidationResult.fail("错误信息")            // 验证失败
 *   ValidationResult.fail("错误", "CODE_001")   // 带错误码
 *   ValidationResult.warn("警告信息")            // 警告（可选支持）
 * </pre>
 * </p>
 */
public final class ValidationResult {

    private static final ValidationResult PASS = new ValidationResult(true, null, null, null);

    private final boolean passed;
    private final String message;
    private final String code;
    private final String suggestion;

    private ValidationResult(boolean passed, String message, String code, String suggestion) {
        this.passed = passed;
        this.message = message;
        this.code = code;
        this.suggestion = suggestion;
    }

    // ========== 静态工厂方法（核心易用性）==========

    /**
     * 验证通过
     */
    public static ValidationResult pass() {
        return PASS;
    }

    /**
     * 验证失败（仅消息）
     */
    public static ValidationResult fail(String message) {
        Objects.requireNonNull(message, "message cannot be null");
        return new ValidationResult(false, message, null, null);
    }

    /**
     * 验证失败（消息 + 错误码）
     */
    public static ValidationResult fail(String message, String code) {
        Objects.requireNonNull(message, "message cannot be null");
        return new ValidationResult(false, message, code, null);
    }

    /**
     * 验证失败（消息 + 错误码 + 修复建议）
     */
    public static ValidationResult fail(String message, String code, String suggestion) {
        Objects.requireNonNull(message, "message cannot be null");
        return new ValidationResult(false, message, code, suggestion);
    }

    /**
     * 警告（非阻断，仅提示）- 可选扩展
     */
    public static ValidationResult warn(String message) {
        Objects.requireNonNull(message, "message cannot be null");
        return new ValidationResult(true, message, "WARN", null);
    }

    // ========== Getters ==========

    public boolean isPassed() {
        return passed;
    }

    public boolean isFailed() {
        return !passed;
    }

    public String getMessage() {
        return message;
    }

    public String getCode() {
        return code;
    }

    public String getSuggestion() {
        return suggestion;
    }

    /**
     * 是否有警告信息（非阻断）
     */
    public boolean hasWarning() {
        return !passed && "WARN".equals(code);
    }

    // ========== 工具方法 ==========

    /**
     * 合并多个验证结果（任一失败则整体失败）
     */
    public static ValidationResult merge(ValidationResult... results) {
        for (ValidationResult r : results) {
            if (r != null && r.isFailed() && !r.hasWarning()) {
                return r; // 返回第一个致命错误
            }
        }
        return PASS;
    }

    @Override
    public String toString() {
        if (passed) return "ValidationResult{passed=true}";
        StringBuilder sb = new StringBuilder("ValidationResult{passed=false, message='")
            .append(message).append("'");
        if (code != null) sb.append(", code='").append(code).append("'");
        if (suggestion != null) sb.append(", suggestion='").append(suggestion).append("'");
        return sb.append("}").toString();
    }
}
