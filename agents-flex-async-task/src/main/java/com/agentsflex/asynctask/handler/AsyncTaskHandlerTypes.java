/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 解析并缓存 {@link AsyncTaskHandler} 提交参数的具体运行时类型。 */
final class AsyncTaskHandlerTypes {
    /** Handler 的泛型签名在类加载后不会变化，因此按实现类缓存解析结果。 */
    private static final ConcurrentMap<Class<?>, Class<?>> CACHE = new ConcurrentHashMap<>();

    private AsyncTaskHandlerTypes() {
    }

    static Class<?> resolve(Class<?> handlerClass) {
        Class<?> cached = CACHE.get(handlerClass);
        if (cached != null) return cached;

        Class<?> resolved = find(handlerClass, Collections.<TypeVariable<?>, Type>emptyMap(),
            new HashSet<Type>());
        if (resolved == null) {
            throw new IllegalStateException("Cannot resolve submit params type for async task handler: "
                + handlerClass.getName() + ". Override getSubmitParamsType() with a concrete class.");
        }
        Class<?> previous = CACHE.putIfAbsent(handlerClass, resolved);
        return previous == null ? resolved : previous;
    }

    /**
     * 沿接口和父类向上查找 AsyncTaskHandler，并在每一层替换已经绑定的类型变量。
     */
    private static Class<?> find(Type current, Map<TypeVariable<?>, Type> inherited, Set<Type> visiting) {
        if (current == null || !visiting.add(current)) return null;
        try {
            Class<?> rawClass;
            Map<TypeVariable<?>, Type> bindings = inherited;
            if (current instanceof ParameterizedType) {
                ParameterizedType parameterized = (ParameterizedType) current;
                if (!(parameterized.getRawType() instanceof Class<?>)) return null;
                rawClass = (Class<?>) parameterized.getRawType();
                bindings = bind(rawClass, parameterized.getActualTypeArguments(), inherited);
                if (rawClass == AsyncTaskHandler.class) {
                    return concreteClass(resolveType(parameterized.getActualTypeArguments()[0], inherited));
                }
            } else if (current instanceof Class<?>) {
                rawClass = (Class<?>) current;
                // 原始 AsyncTaskHandler 已经丢失 P 的运行时信息，不能猜测为 Object。
                if (rawClass == AsyncTaskHandler.class) return null;
            } else {
                return null;
            }

            for (Type genericInterface : rawClass.getGenericInterfaces()) {
                Class<?> found = find(genericInterface, bindings, visiting);
                if (found != null) return found;
            }
            return find(rawClass.getGenericSuperclass(), bindings, visiting);
        } finally {
            visiting.remove(current);
        }
    }

    private static Map<TypeVariable<?>, Type> bind(Class<?> rawClass, Type[] arguments,
                                                    Map<TypeVariable<?>, Type> inherited) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>(inherited);
        TypeVariable<?>[] variables = rawClass.getTypeParameters();
        for (int i = 0; i < variables.length; i++) {
            bindings.put(variables[i], resolveType(arguments[i], inherited));
        }
        return bindings;
    }

    private static Type resolveType(Type type, Map<TypeVariable<?>, Type> bindings) {
        Set<Type> seen = new HashSet<>();
        while (type instanceof TypeVariable<?> && seen.add(type)) {
            Type resolved = bindings.get(type);
            if (resolved == null || resolved == type) break;
            type = resolved;
        }
        return type;
    }

    /** 自动路由采用精确 Class 匹配，因此不把 List&lt;T&gt; 等参数化容器降级为原始 List。 */
    private static Class<?> concreteClass(Type type) {
        return type instanceof Class<?> ? (Class<?>) type : null;
    }
}
