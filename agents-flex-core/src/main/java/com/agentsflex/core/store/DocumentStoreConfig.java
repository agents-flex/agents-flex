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
package com.agentsflex.core.store;

import java.io.Serializable;

/**
 * 文档存储配置的通用契约。
 *
 * <p>具体存储可在实现中检查地址、凭证、集合名等必要配置是否完整。</p>
 */
public interface DocumentStoreConfig extends Serializable {

    /**
     * 检查当前配置是否具备创建或使用存储的必要条件。
     *
     * @return 配置可用时返回 {@code true}
     */
    boolean checkAvailable();
}
