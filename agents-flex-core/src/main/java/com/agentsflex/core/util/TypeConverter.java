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

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 高性能通用类型转换工具类
 * <p>
 * 转换策略优先级（按性能排序）：
 * <ol>
 *   <li>null 处理 & 类型匹配 → 直接返回</li>
 *   <li>基本类型 & 包装类型 → 直接转换（零 JSON 开销）</li>
 *   <li>String/Number/Boolean → 类型安全转换</li>
 *   <li>Date/Time/Enum → 特殊处理（多格式解析 + 缓存）</li>
 *   <li>复杂对象/泛型集合 → 走 JSON 转换（兜底）</li>
 * </ol>
 * <p>
 * 线程安全：本类所有方法均为静态无状态，可安全并发调用
 *
 * @author Michael
 * @since 1.0
 */
public class TypeConverter {

    // ========== 常量定义 ==========

    /**
     * 基本类型 → 包装类型映射
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = new HashMap<>(8);

    static {
        PRIMITIVE_WRAPPER_MAP.put(boolean.class, Boolean.class);
        PRIMITIVE_WRAPPER_MAP.put(byte.class, Byte.class);
        PRIMITIVE_WRAPPER_MAP.put(char.class, Character.class);
        PRIMITIVE_WRAPPER_MAP.put(short.class, Short.class);
        PRIMITIVE_WRAPPER_MAP.put(int.class, Integer.class);
        PRIMITIVE_WRAPPER_MAP.put(long.class, Long.class);
        PRIMITIVE_WRAPPER_MAP.put(float.class, Float.class);
        PRIMITIVE_WRAPPER_MAP.put(double.class, Double.class);
    }

    /**
     * 数值类型集合
     */
    private static final Set<Class<?>> NUMBER_TYPES = new HashSet<>(Arrays.asList(
        Byte.class, Short.class, Integer.class, Long.class,
        Float.class, Double.class, BigDecimal.class, BigInteger.class
    ));

    /**
     * Boolean 真值集合（不区分大小写）
     */
    private static final Set<String> TRUE_VALUES = new HashSet<>(Arrays.asList("true", "1", "yes", "y", "on"));
    /**
     * Boolean 假值集合（不区分大小写）
     */
    private static final Set<String> FALSE_VALUES = new HashSet<>(Arrays.asList("false", "0", "no", "n", "off", ""));

    /**
     * 纯整数正则：可选符号 + 数字
     */
    private static final Pattern PURE_INTEGER_PATTERN = Pattern.compile("^[+-]?\\d+$");

    /**
     * 时间戳正则：10位(秒) 或 13位(毫秒)
     */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\d{10,13}$");

    /**
     * 日期格式列表（按命中率降序排列，提升平均性能）
     */
    private static final String[] DATE_PATTERNS = {
        // 高频格式放前面
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd",
        "yyyy-MM-dd HH:mm",
        "yyyy.MM.dd HH:mm:ss",
        "yyyy.MM.dd",
        "yyyy年MM月dd日",
        "HH:mm:ss",
        "HH:mm:ss.SSS",
        "HH:mm",
        "yyyy-MM-dd hh:mm:ss a",
        "yyyy-MM-dd hh:mm:ss.SSS a",
        "hh:mm:ss a",
        "yyyyMMdd",
        "yyyyMMddHHmmss",
        "yyyyMMddHHmmssSSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE MMM dd HH:mm:ss zzz yyyy",
        "dd-MM-yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "MM-dd-yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss",
    };

    /**
     * DateTimeFormatter 缓存（Key: pattern）
     */
    private static final Map<String, DateTimeFormatter> FORMATTER_CACHE = new ConcurrentHashMap<>(32);


    /**
     * JSON 序列化特性配置
     */
    private static final JSONWriter.Feature[] SERIAL_FEATURES = {
        JSONWriter.Feature.WriteMapNullValue,
    };


    /**
     * 通用类型转换入口
     *
     * @param value  源数据
     * @param toType 目标类型（支持 Class / ParameterizedType）
     * @param <T>    目标类型泛型
     * @return 转换后的对象，失败时抛出 ConversionException
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Type toType) {
        if (value == null) {
            return null;
        }

        // 1. 目标类型是 Class 且已匹配，直接返回（零开销）
        if (toType instanceof Class) {
            Class<?> targetClass = (Class<?>) toType;
            if (targetClass.isInstance(value)) {
                return (T) value;
            }
            return (T) convertSimpleType(value, targetClass);
        }

        // 2. 泛型类型走 JSON 转换（兜底）
        return (T) convertComplexType(value, toType);
    }

    /**
     * 带默认值的转换：转换失败或结果为 null 时返回默认值
     *
     * @param value        源数据
     * @param toType       目标类型
     * @param defaultValue 默认值
     * @param <T>          目标类型泛型
     * @return 转换结果或默认值
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Type toType, T defaultValue) {
        try {
            T result = convert(value, toType);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 动态创建 ParameterizedType（用于运行时泛型）
     *
     * @param rawType  原始类型，如 List.class
     * @param typeArgs 泛型参数，如 User.class
     * @return ParameterizedType 实例
     */
    public static Type createParameterizedType(Class<?> rawType, Type... typeArgs) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return typeArgs.clone();
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    // ========== 简单类型转换（零 JSON 开销）==========

    private static Object convertSimpleType(Object value, Class<?> targetType) {
        // 处理基本类型 → 包装类型
        if (targetType.isPrimitive()) {
            targetType = PRIMITIVE_WRAPPER_MAP.getOrDefault(targetType, targetType);
        }

        // String: 直接 toString
        if (targetType == String.class) {
            return value.toString();
        }

        // 数值类型
        if (NUMBER_TYPES.contains(targetType)) {
            return convertNumber(value, targetType);
        }

        // Boolean
        if (targetType == Boolean.class) {
            return convertBoolean(value);
        }

        // Character
        if (targetType == Character.class) {
            return convertChar(value);
        }

        // Date / Time
        if (targetType == Date.class) {
            return convertToDate(value);
        }
        if (targetType == LocalDate.class) {
            return convertToLocalDate(value);
        }
        if (targetType == LocalDateTime.class) {
            return convertToLocalDateTime(value);
        }
        if (targetType == Instant.class) {
            return convertToInstant(value);
        }

        // Enum
        if (targetType.isEnum()) {
            return convertToEnum(value, (Class<Enum>) targetType);
        }

        // 其他 Class 类型：兜底走 JSON
        return convertByJson(value, targetType);
    }

    // ----- 数值转换 -----
    private static Number convertNumber(Object value, Class<?> targetType) {
        // 源是 Number：直接转换
        if (value instanceof Number) {
            Number num = (Number) value;
            if (targetType == Byte.class) return num.byteValue();
            if (targetType == Short.class) return num.shortValue();
            if (targetType == Integer.class) return num.intValue();
            if (targetType == Long.class) return num.longValue();
            if (targetType == Float.class) return num.floatValue();
            if (targetType == Double.class) return num.doubleValue();
            if (targetType == BigDecimal.class) {
                if (value instanceof BigDecimal) return (BigDecimal) value;
                if (value instanceof Double || value instanceof Float) {
                    return BigDecimal.valueOf(num.doubleValue());
                }
                return new BigDecimal(num.toString());
            }
            if (targetType == BigInteger.class) {
                if (value instanceof BigInteger) return (BigInteger) value;
                return BigInteger.valueOf(num.longValue());
            }
        }

        // 源是 String：校验 + 解析
        if (value instanceof String) {
            String str = (String) value;
            str = str.trim();
            if (!isPureNumeric(str) && !isDecimalString(str)) {
                throw new IllegalArgumentException("字符串 '" + str + "' 不是有效数值");
            }
            try {
                if (targetType == Integer.class) return Integer.parseInt(str);
                if (targetType == Long.class) return Long.parseLong(str);
                if (targetType == Double.class) return Double.parseDouble(str);
                if (targetType == Float.class) return Float.parseFloat(str);
                if (targetType == BigDecimal.class) return new BigDecimal(str);
                if (targetType == BigInteger.class) return new BigInteger(str);
                if (targetType == Byte.class) return Byte.parseByte(str);
                if (targetType == Short.class) return Short.parseShort(str);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法将 '" + str + "' 转换为 " + targetType.getSimpleName(), e);
            }
        }

        throw new IllegalArgumentException("无法将 " + value + " (" + value.getClass().getSimpleName() + ") 转换为 " + targetType);
    }

    /**
     * 判断是否为纯整数字符串（可选符号 + 数字）
     */
    private static boolean isPureNumeric(String str) {
        return PURE_INTEGER_PATTERN.matcher(str).matches();
    }

    /**
     * 判断是否为小数字符串（包含小数点）
     */
    private static boolean isDecimalString(String str) {
        if (str == null || str.isEmpty()) return false;
        int dotCount = 0;
        int start = (str.charAt(0) == '+' || str.charAt(0) == '-') ? 1 : 0;
        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '.') {
                if (++dotCount > 1) return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dotCount == 1;
    }

    // ----- Boolean 转换 -----
    private static Boolean convertBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            Number num = (Number) value;
            return num.intValue() != 0;
        }
        if (value instanceof String) {
            String str = (String) value;
            String s = str.trim().toLowerCase(Locale.ROOT);
            if (FALSE_VALUES.contains(s)) return false;
            if (TRUE_VALUES.contains(s)) return true;
            // 非明确真/假值：非 null 即 true（可配置）
            return value != null;
        }
        return value != null;
    }

    // ----- Character 转换 -----
    private static Character convertChar(Object value) {
        if (value instanceof Character) return (Character) value;
        if (value instanceof String) {
            String str = (String) value;
            if (str.isEmpty()) {
                throw new IllegalArgumentException("空字符串无法转换为 Character");
            }
            return str.charAt(0);
        }
        if (value instanceof Number) {
            Number num = (Number) value;
            return (char) num.intValue();
        }
        throw new IllegalArgumentException("无法将 " + value + " 转换为 Character");
    }

    // ----- Date 转换（多格式 + 缓存 + 时间戳优先）-----
    private static Date convertToDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date) return (Date) value;
        if (value instanceof Number) {
            Number num = (Number) value;
            return new Date(num.longValue());
        }
        if (value instanceof String) {
            String str = (String) value;
            return parseDateFromString(str);
        }
        return parseDateFromString(value.toString());
    }

    private static Date parseDateFromString(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("日期字符串不能为空");
        }
        str = str.trim();

        // 策略1: 纯数字时间戳（10位秒 / 13位毫秒）
        if (TIMESTAMP_PATTERN.matcher(str).matches()) {
            long ts = Long.parseLong(str);
            if (str.length() <= 10) ts *= 1000; // 秒 → 毫秒
            return new Date(ts);
        }

        // 策略2: ISO_INSTANT 格式（带 Z / 时区）
        try {
            if (str.endsWith("Z") || str.contains("+") || (str.indexOf('-', 10) > 0 && !str.contains(" "))) {
                Instant instant = Instant.parse(str);
                return Date.from(instant);
            }
        } catch (DateTimeParseException ignore) {
        }

        // 策略3: 预定义格式轮询（缓存 Formatter）
        for (String pattern : DATE_PATTERNS) {
            try {
                DateTimeFormatter formatter = getCachedFormatter(pattern);
                TemporalAccessor accessor;
                if (pattern.contains("HH") || pattern.contains("hh") || pattern.contains("H")) {
                    accessor = LocalDateTime.parse(str, formatter);
                    return Date.from(((LocalDateTime) accessor)
                        .atZone(ZoneId.systemDefault())
                        .toInstant());
                } else {
                    accessor = LocalDate.parse(str, formatter);
                    return Date.from(((LocalDate) accessor)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant());
                }
            } catch (DateTimeParseException ignore) {
                // 尝试下一个格式
            }
        }

        // 策略4: 宽松解析兜底
        Date fuzzy = tryFuzzyParse(str);
        if (fuzzy != null) return fuzzy;

        throw new IllegalArgumentException("无法解析日期: '" + str + "'");
    }

    private static DateTimeFormatter getCachedFormatter(String pattern) {
        return FORMATTER_CACHE.computeIfAbsent(pattern, p ->
            DateTimeFormatter.ofPattern(p)
                .withLocale(Locale.CHINA)
                .withZone(ZoneId.systemDefault())
        );
    }

    private static Date tryFuzzyParse(String str) {
        try {
            // 移除中文、统一分隔符
            String cleaned = str.replaceAll("[\\u4e00-\\u9fa5]", "")
                .replaceAll("[./]", "-")
                .trim();

            // 尝试宽松格式：yyyy-M-d [H:m:s[.S]]
            if (cleaned.matches("\\d{4}-\\d{1,2}-\\d{1,2}( \\d{1,2}:\\d{1,2}(:\\d{1,2}(\\.\\d{1,3})?)?)?")) {
                DateTimeFormatter flexible = new DateTimeFormatterBuilder()
                    .parseLenient()
                    .appendPattern("yyyy-M-d")
                    .optionalStart().appendLiteral(' ').appendPattern("H:m:s").optionalEnd()
                    .optionalStart().appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true).optionalEnd()
                    .toFormatter()
                    .withZone(ZoneId.systemDefault());

                if (cleaned.contains(":")) {
                    LocalDateTime ldt = LocalDateTime.parse(cleaned, flexible);
                    return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
                } else {
                    LocalDate ld = LocalDate.parse(cleaned, flexible);
                    return Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
                }
            }

            // 尝试 ISO_DATE_TIME
            try {
                LocalDateTime ldt = LocalDateTime.parse(str, DateTimeFormatter.ISO_DATE_TIME);
                return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
            } catch (DateTimeParseException ignore) {
            }

        } catch (Exception ignore) {
            // 模糊解析失败
        }
        return null;
    }

    // ----- LocalDate 转换 -----
    private static LocalDate convertToLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof Date) {
            Date date = (Date) value;
            return Instant.ofEpochMilli(date.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        }
        if (value instanceof Long) {
            long ts = (Long) value;
            return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        }
        if (value instanceof String) {
            String str = (String) value;
            // 优先尝试 ISO 格式
            try {
                return LocalDate.parse(str.trim(), DateTimeFormatter.ISO_DATE);
            } catch (DateTimeParseException e) {
                // 回退到多格式解析
                Date date = parseDateFromString(str);
                return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }
        throw new IllegalArgumentException("无法将 " + value + " 转换为 LocalDate");
    }

    // ----- LocalDateTime 转换 -----
    private static LocalDateTime convertToLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Date) {
            Date date = (Date) value;
            return Instant.ofEpochMilli(date.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        }
        if (value instanceof Long) {
            long ts = (Long) value;
            return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        }
        if (value instanceof String) {
            String str = (String) value;
            try {
                return LocalDateTime.parse(str.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                Date date = parseDateFromString(str);
                return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            }
        }
        throw new IllegalArgumentException("无法将 " + value + " 转换为 LocalDateTime");
    }

    // ----- Instant 转换 -----
    private static Instant convertToInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof Date) {
            Date date = (Date) value;
            return date.toInstant();
        }
        if (value instanceof Long) {
            long ts = (Long) value;
            return Instant.ofEpochMilli(ts);
        }
        if (value instanceof String) {
            String str = (String) value;
            try {
                return Instant.parse(str.trim());
            } catch (DateTimeParseException e) {
                Date date = parseDateFromString(str);
                return date.toInstant();
            }
        }
        throw new IllegalArgumentException("无法将 " + value + " 转换为 Instant");
    }

    // ----- Enum 转换 -----
    private static <E extends Enum<E>> E convertToEnum(Object value, Class<E> enumClass) {
        if (enumClass.isInstance(value)) {
            return (E) value;
        }
        if (value instanceof String) {
            String name = (String) value;
            return Enum.valueOf(enumClass, name.trim());
        }
        if (value instanceof Number) {
            Number num = (Number) value;
            E[] constants = enumClass.getEnumConstants();
            int index = num.intValue();
            if (index < 0 || index >= constants.length) {
                throw new IllegalArgumentException("枚举索引 " + index + " 超出范围 [0, " + (constants.length - 1) + "]");
            }
            return constants[index];
        }
        throw new IllegalArgumentException("无法将 " + value + " 转换为枚举 " + enumClass.getSimpleName());
    }

    // ========== 复杂类型转换（JSON 兜底）==========

    private static Object convertComplexType(Object value, Type toType) {
        try {
            if (value instanceof JSONArray) {
                return ((JSONArray) value).to(toType);
            }

            if (value instanceof JSONObject) {
                return ((JSONObject) value).to(toType);
            }

            // 优化：如果 value 已是 JSON 字符串，直接解析
            if (value instanceof String && !(toType instanceof Class)) {
                String jsonStr = (String) value;
                return JSON.parseObject(jsonStr, toType);
            }

            String json = JSON.toJSONString(value, SERIAL_FEATURES);
            return JSON.parseObject(json, toType);
        } catch (RuntimeException e) {
            throw new ConversionException("复杂类型转换失败: " + toType.getTypeName(), e);
        } catch (Error e) {
            // Error 不应被捕获，直接抛出
            throw e;
        }
    }

    private static Object convertByJson(Object value, Class<?> targetType) {
        try {
            if (value instanceof JSONArray) {
                return ((JSONArray) value).to(targetType);
            }

            if (value instanceof JSONObject) {
                return ((JSONObject) value).to(targetType);
            }
            String json = JSON.toJSONString(value, SERIAL_FEATURES);
            return JSON.parseObject(json, targetType);
        } catch (RuntimeException e) {
            throw new ConversionException("JSON 转换失败: " + targetType.getName(), e);
        } catch (Error e) {
            throw e;
        }
    }


    /**
     * 类型转换异常
     */
    public static class ConversionException extends RuntimeException {
        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }

        public ConversionException(String message) {
            super(message);
        }
    }

}
