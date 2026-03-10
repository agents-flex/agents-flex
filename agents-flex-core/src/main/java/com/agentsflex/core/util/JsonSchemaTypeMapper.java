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
package com.agentsflex.core.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 类型到 JSON Schema 类型的映射工具类
 * <p>
 * 支持标准类型、集合类型、泛型类型及自定义类型注册
 * <p>
 * JSON Schema 标准类型: string, number, integer, boolean, object, array, null
 *
 * @author fuhai
 * @since 2026/03/10
 */
public final class JsonSchemaTypeMapper {

    /**
     * 类型映射缓存：避免重复反射解析，提升性能
     */
    private static final Map<Class<?>, String> TYPE_CACHE = new ConcurrentHashMap<>(64);

    /**
     * 自定义类型映射策略（可扩展）
     */
    private static final Map<Class<?>, TypeMappingStrategy> CUSTOM_STRATEGIES = new ConcurrentHashMap<>(8);

    /**
     * 私有构造，防止实例化
     */
    private JsonSchemaTypeMapper() {
    }

    /**
     * 将 Java Class 映射为 JSON Schema 类型字符串
     *
     * @param javaType Java 类型
     * @return JSON Schema 类型: string/number/integer/boolean/object/array/null
     */
    @NotNull
    public static String mapToSchemaType(@Nullable Class<?> javaType) {
        if (javaType == null) {
            return "string";
        }

        // 1. 优先检查自定义策略
        TypeMappingStrategy custom = CUSTOM_STRATEGIES.get(javaType);
        if (custom != null) {
            return custom.mapType(javaType);
        }

        // 2. 缓存命中
        return TYPE_CACHE.computeIfAbsent(javaType, JsonSchemaTypeMapper::doMapType);
    }

    /**
     * 解析数组/集合元素的 JSON Schema 类型（支持泛型）
     *
     * @param genericType 泛型类型信息，如 {@code List<String>} 的 {@code String}
     * @return 元素类型的 JSON Schema 类型
     */
    @NotNull
    public static String resolveArrayItemType(@Nullable Type genericType) {
        if (genericType == null) {
            return "string";
        }

        // 1. ParameterizedType: List<String>, Map<K,V>
        if (genericType instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
            if (args.length > 0) {
                return resolveType(args[0]);
            }
        }

        // 2. GenericArrayType: T[], List<T>[]
        if (genericType instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) genericType).getGenericComponentType();
            return resolveType(componentType);
        }

        // 3. WildcardType: List<? extends Number> → 取上界
        if (genericType instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) genericType).getUpperBounds();
            if (upperBounds.length > 0) {
                return resolveType(upperBounds[0]);
            }
            Type[] lowerBounds = ((WildcardType) genericType).getLowerBounds();
            if (lowerBounds.length > 0) {
                return resolveType(lowerBounds[0]);
            }
            return "object";
        }

        // 4. TypeVariable: List<T> → 默认 object（可从上下文进一步解析）
        if (genericType instanceof TypeVariable) {
            return "object";
        }

        // 5. 普通 Class
        if (genericType instanceof Class) {
            return mapToSchemaType((Class<?>) genericType);
        }

        // 6. 兜底：最宽兼容策略
        return "object";
    }

    /**
     * 注册自定义类型映射策略
     * <p>
     * 示例：{@code registerStrategy(LocalDateTime.class, t -> "string", "date-time")}
     *
     * @param type     Java 类型
     * @param strategy 映射策略
     */
    public static void registerStrategy(@NotNull Class<?> type, @NotNull TypeMappingStrategy strategy) {
        CUSTOM_STRATEGIES.put(type, strategy);
        TYPE_CACHE.remove(type); // 清除缓存，确保新策略生效
    }

    /**
     * 清除缓存（测试或热更新场景使用）
     */
    public static void clearCache() {
        TYPE_CACHE.clear();
    }

    // =============== 内部映射逻辑 ===============

    @NotNull
    private static String doMapType(@NotNull Class<?> type) {
        // 1. 数组类型（含多维）
        if (type.isArray()) {
            return "array";
        }

        // 2. Optional 类型：解包或返回 string（根据业务需求调整）
        if (Optional.class.isAssignableFrom(type)) {
            return "string";
        }

        // 3. Map → object
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }

        // 4. Collection → array
        if (Collection.class.isAssignableFrom(type)) {
            return "array";
        }

        // 5. 整数类型（使用类型层次判断，避免字符串匹配）
        if (isIntegerNumericType(type)) {
            return "integer";
        }

        // 6. 浮点类型
        if (isFloatingNumericType(type)) {
            return "number";
        }

        // 7. 布尔类型
        if (boolean.class == type || Boolean.class == type) {
            return "boolean";
        }

        // 8. 字符串及类字符串类型
        if (isStringLikeType(type)) {
            return "string";
        }

        // 9. 枚举类型
        if (type.isEnum()) {
            return "string";
        }

        // 10. 未知类型（实体类/自定义类）：默认 object（符合 JSON Schema 规范）
        // 大模型传参是 JSON 对象，Java 实体类应对应 object 类型
        return "object";
    }

    @NotNull
    private static String resolveType(@Nullable Type type) {
        if (type instanceof Class) {
            return mapToSchemaType((Class<?>) type);
        } else if (type instanceof ParameterizedType) {
            Class<?> rawType = (Class<?>) ((ParameterizedType) type).getRawType();
            return mapToSchemaType(rawType);
        } else {
            // 递归处理其他 Type 子类型
            return resolveArrayItemType(type);
        }
    }

    // =============== 类型判断辅助方法 ===============

    private static boolean isIntegerNumericType(Class<?> type) {
        return type == int.class || type == Integer.class ||
            type == long.class || type == Long.class ||
            type == short.class || type == Short.class ||
            type == byte.class || type == Byte.class ||
            type == BigInteger.class;
    }

    private static boolean isFloatingNumericType(Class<?> type) {
        return type == float.class || type == Float.class ||
            type == double.class || type == Double.class ||
            type == BigDecimal.class;
    }

    private static boolean isStringLikeType(Class<?> type) {
        return type == String.class ||
            type == char.class || type == Character.class ||
            type == CharSequence.class ||
            // 时间类型（大模型通常以字符串传输）
            type == Date.class ||
            type == java.sql.Date.class ||
            type == java.sql.Timestamp.class ||
            type == LocalDate.class ||
            type == LocalDateTime.class ||
            type == LocalTime.class ||
            type == Instant.class ||
            type == ZonedDateTime.class ||
            type == OffsetDateTime.class ||
            // 其他常见字符串语义类型
            type == UUID.class ||
            type == URI.class ||
            type == URL.class;
    }

    /**
     * 类型映射策略函数式接口（支持扩展）
     */
    @FunctionalInterface
    public interface TypeMappingStrategy {
        /**
         * 将 Java 类型映射为 JSON Schema 类型
         *
         * @param javaType Java 类型
         * @return JSON Schema 类型字符串
         */
        @NotNull
        String mapType(@NotNull Class<?> javaType);
    }
}
