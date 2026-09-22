package com.agentsflex.graph.identifier;


import java.util.regex.Pattern;

/**
 * 校验跨数据库可移植的标签、边类型、属性名和别名。
 */
public final class GraphIdentifiers {
    /**
     * 可直接渲染到 Cypher、nGQL 等方言中的安全标识符格式。
     */
    private static final Pattern PORTABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private GraphIdentifiers() {
    }

    /**
     * 校验一个数据库标识符。
     *
     * @param value 待校验值
     * @param name  错误信息中使用的字段名
     * @return 原始值
     * @throws IllegalArgumentException 值为空或包含不安全字符时抛出
     */
    public static String requireValid(String value, String name) {
        if (!isValid(value)) {
            throw new IllegalArgumentException(name + " must match " + PORTABLE.pattern());
        }
        return value;
    }

    /**
     * 判断名称是否满足跨后端可移植标识符规则。
     *
     * <p>该方法不会抛出异常，适合用于 Schema 反查：某个数据库中的历史名称
     * 不可移植时，可以跳过该项并通过 warning 告知调用方，而不是让整次反查失败。</p>
     */
    public static boolean isValid(String value) {
        return value != null && PORTABLE.matcher(value).matches();
    }

    /**
     * @return 非空文本；为空或全空白时抛出异常
     */
    public static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
