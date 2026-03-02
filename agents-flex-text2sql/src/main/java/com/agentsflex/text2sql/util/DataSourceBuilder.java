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
package com.agentsflex.text2sql.util;


import com.agentsflex.core.util.StringUtil;
import com.agentsflex.core.util.TypeConverter;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class DataSourceBuilder {

    private static final Map<String, String> dataSourceAlias = new HashMap<>();

    static {
        dataSourceAlias.put("druid", "com.alibaba.druid.pool.DruidDataSource");
        dataSourceAlias.put("hikari", "com.zaxxer.hikari.HikariDataSource");
        dataSourceAlias.put("hikaricp", "com.zaxxer.hikari.HikariDataSource");
        dataSourceAlias.put("dbcp", "org.apache.commons.dbcp2.BasicDataSource");
        dataSourceAlias.put("dbcp2", "org.apache.commons.dbcp2.BasicDataSource");
    }

    private final Map<String, String> dataSourceProperties;

    public DataSourceBuilder(Map<String, String> dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    public DataSource build() {
        String dataSourceClassName = null;
        String type = dataSourceProperties.get("type");
        if (StringUtil.hasText(type)) {
            dataSourceClassName = dataSourceAlias.getOrDefault(type, type);
        } else {
            dataSourceClassName = detectDataSourceClass();
        }


        if (StringUtil.noText(dataSourceClassName)) {
            if (StringUtil.noText(type)) {
                throw new IllegalStateException("The dataSource type can not be null or blank.");
            } else {
                throw new IllegalStateException("Can not find the dataSource type: " + type);
            }
        }

        try {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            Class<?> dataSourceClass = Class.forName(dataSourceClassName, false, contextClassLoader);
            Object dataSourceObject = dataSourceClass.newInstance();
            setDataSourceProperties(dataSourceObject);
            return (DataSource) dataSourceObject;
        } catch (Exception e) {
            throw new IllegalStateException("Can not new instance dataSource object by class:  " + dataSourceClassName);
        }
    }

    /**
     * 通过反射动态设置 dataSourceObject 的属性值
     *
     * @param dataSourceObject 数据源对象
     * @throws Exception 反射调用异常
     */
    private void setDataSourceProperties(Object dataSourceObject) throws Exception {
        if (dataSourceProperties == null || dataSourceProperties.isEmpty()) {
            return;
        }

        Class<?> clazz = dataSourceObject.getClass();

        for (Map.Entry<String, String> entry : dataSourceProperties.entrySet()) {
            String attr = entry.getKey();
            String value = entry.getValue();
            String camelAttr = attrToCamel(attr);

            try {
                // 特殊处理 url/jdbcUrl 兼容不同数据源的属性命名
                Method setter = null;
                if ("url".equals(camelAttr) || "jdbcUrl".equals(camelAttr)) {
                    setter = findSetter(clazz, "url");
                    if (setter == null) {
                        setter = findSetter(clazz, "jdbcUrl");
                    }
                } else {
                    setter = findSetter(clazz, camelAttr);
                }

                if (setter != null) {
                    // 类型转换后调用 setter
                    Class<?> paramType = setter.getParameterTypes()[0];
                    Object convertedValue = TypeConverter.convert(value, paramType);
                    if (convertedValue == null) {
                        // 未转换成功，使用原始值
                        convertedValue = value;
                    }
                    setter.invoke(dataSourceObject, convertedValue);
                }
                // 可选：未找到 setter 时记录警告日志
                // log.warn("No setter found for property: {} on class: {}", camelAttr, clazz.getName());

            } catch (Exception e) {
                // 包装异常，保留上下文信息便于排查
                throw new RuntimeException("Failed to set property '" + camelAttr +
                    "' on " + clazz.getName(), e);
            }
        }
    }

    /**
     * 查找指定属性的 setter 方法（支持继承的 public 方法）
     *
     * @param clazz        目标类
     * @param propertyName 属性名（驼峰命名，如 url、maxPoolSize）
     * @return 找到的 Method 对象，未找到返回 null
     */
    private Method findSetter(Class<?> clazz, String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            return null;
        }

        String methodName = "set" + capitalize(propertyName);

        // 遍历所有 public 方法（包括父类继承的）
        for (Method method : clazz.getMethods()) {
            if (methodName.equals(method.getName())
                && method.getParameterCount() == 1
                && method.getReturnType() == void.class) {
                return method;
            }
        }
        return null;
    }

    /**
     * 字符串首字母大写（JavaBean 规范）
     */
    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        // 处理单字符情况
        if (name.length() == 1) {
            return name.toUpperCase();
        }
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }


    public static String attrToCamel(String string) {
        int strLen = string.length();
        StringBuilder sb = new StringBuilder(strLen);
        for (int i = 0; i < strLen; i++) {
            char c = string.charAt(i);
            if (c == '-') {
                if (++i < strLen) {
                    sb.append(Character.toUpperCase(string.charAt(i)));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }


    private String detectDataSourceClass() {
        String[] detectClassNames = new String[]{
            "com.alibaba.druid.pool.DruidDataSource",
            "com.zaxxer.hikari.HikariDataSource",
            "cn.beecp.BeeDataSource",
            "org.apache.commons.dbcp2.BasicDataSource",
        };

        for (String detectClassName : detectClassNames) {
            String result = doDetectDataSourceClass(detectClassName);
            if (result != null) {
                return result;
            }
        }

        return null;
    }


    private String doDetectDataSourceClass(String className) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Class.forName(className, false, contextClassLoader);
            return className;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

}
