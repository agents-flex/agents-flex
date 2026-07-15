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
package com.agentsflex.core.model.image;

import com.agentsflex.core.model.config.BaseModelConfig;

/**
 * 图片模型适配器的基础实现。
 * <p>该类统一保存供应商配置，并在构造阶段保证配置不为空；具体 HTTP 协议和响应解析由子类实现。</p>
 *
 * @param <T> 具体供应商的图片模型配置类型
 */
public abstract class BaseImageModel<T extends BaseModelConfig> implements ImageModel {

    /**
     * 当前适配器使用的配置对象，包含连接信息、鉴权信息、默认模型和供应商能力声明。
     * <p>字段由子类直接读取；调用方应避免在请求执行期间并发修改同一配置对象。</p>
     */
    protected T config;

    /**
     * 创建图片模型适配器。
     *
     * @param config 非空的供应商配置
     * @throws IllegalArgumentException 当配置为空时抛出
     */
    public BaseImageModel(T config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    /**
     * 获取当前适配器配置。
     *
     * @return 构造模型时传入的配置对象
     */
    public T getConfig() {
        return config;
    }

}
