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
package com.agentsflex.chain;

public enum ChainStatus {

    READY(0), // 未开始执行
    START(1), // 已开始执行，执行中...
    PAUSE_FOR_WAKE_UP(5), //暂停等待唤醒
    PAUSE_FOR_INPUT(6), //暂停等待数据输入
    ERROR(7), //发生错误
    FINISHED_NORMAL(10), //正常结束
    FINISHED_ABNORMAL(11), //错误结束
    ;

    final int value;

    ChainStatus(int value) {
        this.value = value;
    }
}
