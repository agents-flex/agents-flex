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

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一个链式节点校验结果。
 * 包含校验是否成功、消息说明以及附加的详细信息（如失败字段、原因等）。
 * <p>
 * 实例是不可变的（immutable），线程安全。
 */
public class ChainNodeValidResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final ChainNodeValidResult SUCCESS = new ChainNodeValidResult(true, null, null);
    public static final ChainNodeValidResult FAILURE = new ChainNodeValidResult(false, null, null);

    private final boolean success;
    private final String message;
    private final Map<String, Object> details;

    /**
     * 私有构造器，确保通过工厂方法创建实例。
     */
    private ChainNodeValidResult(boolean success, String message, Map<String, Object> details) {
        this.success = success;
        this.message = message;
        // 防御性拷贝，防止外部修改
        this.details = details != null ? Collections.unmodifiableMap(new java.util.HashMap<>(details)) : null;
    }

    /**
     * 获取校验是否成功。
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取结果消息（可为 null）。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取详细信息（如校验失败的字段、原因等），不可变 Map。
     * 如果无详情，则返回 null。
     */
    public Map<String, Object> getDetails() {
        return details;
    }

    // ------------------ 静态工厂方法 ------------------

    /**
     * 创建一个成功的校验结果（无消息、无详情）。
     */
    public static ChainNodeValidResult ok() {
        return SUCCESS;
    }

    /**
     * 创建一个成功的校验结果，附带消息。
     */
    public static ChainNodeValidResult ok(String message) {
        return new ChainNodeValidResult(true, message, null);
    }

    /**
     * 创建一个成功的校验结果，附带消息和详情。
     */
    public static ChainNodeValidResult ok(String message, Map<String, Object> details) {
        return new ChainNodeValidResult(true, message, details);
    }

    /**
     * 创建一个成功的校验结果，支持键值对形式传入 details。
     * <p>
     * 示例：success("验证通过", "userId", 123, "role", "admin")
     *
     * @param message 消息
     * @param kvPairs 键值对（必须成对：key1, value1, key2, value2...）
     * @return ChainNodeValidResult
     * @throws IllegalArgumentException 如果 kvPairs 数量为奇数
     */
    public static ChainNodeValidResult ok(String message, Object... kvPairs) {
        Map<String, Object> details = toMapFromPairs(kvPairs);
        return new ChainNodeValidResult(true, message, details);
    }


    /**
     * 创建一个失败的校验结果（无消息、无详情）。
     */
    public static ChainNodeValidResult fail() {
        return FAILURE;
    }

    /**
     * 创建一个失败的校验结果，仅包含消息。
     */
    public static ChainNodeValidResult fail(String message) {
        return new ChainNodeValidResult(false, message, null);
    }

    /**
     * 创建一个失败的校验结果，包含消息和详情。
     */
    public static ChainNodeValidResult fail(String message, Map<String, Object> details) {
        return new ChainNodeValidResult(false, message, details);
    }

    /**
     * 创建一个失败的校验结果，支持键值对形式传入 details。
     * <p>
     * 示例：fail("验证失败", "field", "email", "reason", "格式错误")
     */
    public static ChainNodeValidResult fail(String message, Object... kvPairs) {
        Map<String, Object> details = toMapFromPairs(kvPairs);
        return new ChainNodeValidResult(false, message, details);
    }

    /**
     * 快捷方法：创建包含字段错误的失败结果。
     * 适用于表单/参数校验场景。
     *
     * @param field  错误字段名
     * @param reason 错误原因
     * @return 失败结果
     */
    public static ChainNodeValidResult failOnField(String field, String reason) {
        Map<String, Object> details = Collections.singletonMap("fieldError", field + ": " + reason);
        return fail(reason, details);
    }

    /**
     * 快捷方法：基于布尔值返回成功或失败结果。
     *
     * @param condition     条件
     * @param messageIfFail 条件不满足时的消息
     * @return 根据条件返回对应结果
     */
    public static ChainNodeValidResult require(boolean condition, String messageIfFail) {
        return condition ? ok() : fail(messageIfFail);
    }


    private static Map<String, Object> toMapFromPairs(Object... kvPairs) {
        if (kvPairs == null || kvPairs.length == 0) {
            return null;
        }

        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("kvPairs must be even-sized: key1, value1, key2, value2...");
        }

        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            Object key = kvPairs[i];
            Object value = kvPairs[i + 1];

            if (!(key instanceof String)) {
                throw new IllegalArgumentException("Key must be a String, but got: " + key);
            }

            map.put((String) key, value);
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChainNodeValidResult)) return false;
        ChainNodeValidResult that = (ChainNodeValidResult) o;
        return success == that.success &&
            Objects.equals(message, that.message) &&
            Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, message, details);
    }

    @Override
    public String toString() {
        return "ChainNodeValidResult{" +
            "success=" + success +
            ", message='" + message + '\'' +
            ", details=" + details +
            '}';
    }
}
