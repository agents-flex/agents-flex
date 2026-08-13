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
package com.agentsflex.asynctask.store;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 使用 fastjson2 JSONB 保存异步任务的默认序列化器。
 *
 * <p>任务的提交参数、查询参数、结果和 metadata 可能包含多态对象，因此需要保存类名。
 * 反序列化不会开放无限制 AutoType，只允许框架类、常用 JDK 类型以及构造器显式加入的
 * 业务包前缀。业务 DTO 不在默认白名单中时，必须增加尽可能精确的包前缀。</p>
 */
public final class FastjsonAsyncTaskStoreSerializer implements AsyncTaskStoreSerializer {
    private static final String[] DEFAULT_PREFIXES = {
        "com.agentsflex.asynctask.", "com.agentsflex.core.", "java.lang.",
        "java.math.", "java.time.", "java.util."
    };
    private final JSONReader.AutoTypeBeforeHandler autoTypeFilter;

    /**
     * 创建只接受框架类型和常用 JDK 类型的序列化器。
     */
    public FastjsonAsyncTaskStoreSerializer() {
        this(new String[0]);
    }

    /**
     * 创建允许恢复额外业务类型的序列化器。
     *
     * @param additionalAcceptedTypePrefixes 完整类名或以点结尾的业务包前缀
     */
    public FastjsonAsyncTaskStoreSerializer(String... additionalAcceptedTypePrefixes) {
        String[] additional = additionalAcceptedTypePrefixes == null
            ? new String[0] : additionalAcceptedTypePrefixes.clone();
        String[] accepted = Arrays.copyOf(DEFAULT_PREFIXES, DEFAULT_PREFIXES.length + additional.length);
        for (int i = 0; i < additional.length; i++) {
            if (additional[i] == null || additional[i].trim().isEmpty())
                throw new IllegalArgumentException("accepted type prefix must not be blank");
            accepted[DEFAULT_PREFIXES.length + i] = additional[i];
        }
        autoTypeFilter = JSONReader.autoTypeFilter(true, accepted);
    }

    @Override
    public byte[] serialize(Serializable value) {
        try {
            // WriteClassName 保留 Object 字段实际类型，FieldBased 支持没有 setter 的业务 DTO。
            return JSONB.toBytes(value, JSONWriter.Feature.WriteClassName, JSONWriter.Feature.FieldBased,
                JSONWriter.Feature.ReferenceDetection, JSONWriter.Feature.ErrorOnNoneSerializable,
                JSONWriter.Feature.NotWriteHashMapArrayListClassName);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Async task contains a non-serializable value", error);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        if (bytes == null) return null;
        if (type == null) throw new IllegalArgumentException("target type must not be null");
        try {
            // 在读取持久化类名之前执行白名单过滤，不能对 Store 内容开放全局 AutoType。
            return JSONB.parseObject(bytes, type, autoTypeFilter, JSONReader.Feature.FieldBased,
                JSONReader.Feature.UseNativeObject, JSONReader.Feature.ErrorOnNotSupportAutoType);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Stored async task cannot be decoded as " + type.getName(), error);
        }
    }
}
