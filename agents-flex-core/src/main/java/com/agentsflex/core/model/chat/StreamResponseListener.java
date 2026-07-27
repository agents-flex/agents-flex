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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import org.slf4j.Logger;

/**
 * 流式聊天响应监听器，用于接收一次流式请求从打开到关闭的生命周期事件。
 *
 * <p>正常情况下，回调顺序如下：</p>
 * <pre>{@code
 * onOpen -> onMessage（零次或多次）-> onClose
 * }</pre>
 *
 * <p>发生异常时，回调顺序如下：</p>
 * <pre>{@code
 * onOpen -> onMessage（零次或多次）-> onError -> onClose
 * }</pre>
 *
 * <p>{@link #onOpen(StreamContext)} 和 {@link #onError(StreamContext, Throwable)} 最多各调用一次。
 * 一旦调用了 {@code onOpen}，框架保证最终调用且仅调用一次
 * {@link #onClose(StreamContext)}；无论请求正常结束还是异常结束，{@code onClose} 都是最后一个生命周期回调。
 * 如果请求在流打开前失败，则可能只调用 {@code onError}。</p>
 *
 * <p>回调可能由网络线程或其他异步线程执行，监听器实现不应假定回调线程与发起
 * {@code chatStream} 调用的线程相同。</p>
 */
public interface StreamResponseListener {

    Logger logger = org.slf4j.LoggerFactory.getLogger(StreamResponseListener.class);

    /**
     * 通知流式请求的生命周期已经打开。
     *
     * <p>该回调表示框架开始处理本次流式请求，不保证底层网络连接此时已经建立成功。</p>
     *
     * @param context 本次流式请求的上下文
     */
    default void onOpen(StreamContext context) {
    }

    /**
     * 通知监听器收到一条流式响应消息。
     *
     * <p>该方法可能被调用零次或多次。处理中间消息时，响应中的 {@code message.content}
     * 表示本次收到的增量内容，{@code message.fullContent} 表示截至当前累计的完整内容。
     * 正常结束前，框架还会发送一条 {@code message.finished == true} 的最终消息，其中包含
     * 合并后的完整响应。</p>
     *
     * @param context  本次流式请求的上下文
     * @param response 本次收到的响应消息
     */
    void onMessage(StreamContext context, AiMessageResponse response);

    /**
     * 通知监听器流式请求处理过程中发生异常。
     *
     * <p>该方法最多调用一次。流已经打开时，调用本方法后仍会调用
     * {@link #onClose(StreamContext)}，因此异常处理应放在本方法中，公共资源释放应放在
     * {@code onClose} 中。默认实现会将异常输出到标准错误并写入错误日志。</p>
     *
     * @param context 本次流式请求的上下文；可通过该上下文查询异常状态
     * @param err     导致请求失败的异常
     */
    default void onError(StreamContext context, Throwable err) {
        if (err != null) {
            err.printStackTrace();
            logger.error(err.toString(), err);
        }
    }

    /**
     * 通知流式请求的生命周期已经关闭。
     *
     * <p>流打开后，无论请求正常结束还是异常结束，该方法都会且只会调用一次，并且是最后一个
     * 生命周期回调。可通过 {@link StreamContext#isError()} 判断关闭前是否发生异常，通过
     * {@link StreamContext#getThrowable()} 获取对应异常；正常结束时可通过
     * {@link StreamContext#getFullMessage()} 获取完整消息。</p>
     *
     * @param context 本次流式请求的上下文
     */
    default void onClose(StreamContext context) {
    }
}
