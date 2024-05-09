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
package com.agentsflex.message;

import java.io.Serializable;

/**
 * 消息状态，用于在流式（stream）的场景下，用于标识当前消息的状态
 */
public enum MessageStatus implements Serializable {

    /**
     * 开始内容
     */
    START(1),

    /**
     * 中间内容
     */
    MIDDLE(2),

    /**
     * 结束内容，一般情况下指的是最后一条内容
     */
    END(3),

    /**
     * 其他内容
     */
    UNKNOW(9),
    ;
    private int value;

    MessageStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
