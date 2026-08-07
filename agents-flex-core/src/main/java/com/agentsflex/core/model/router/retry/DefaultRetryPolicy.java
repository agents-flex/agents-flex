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
package com.agentsflex.core.model.router.retry;

import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.exception.ModelOverloadedException;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 默认重试策略。
 */
public class DefaultRetryPolicy implements RetryPolicy {

    /**
     * 最大重试次数。
     */
    private final int maxRetries;

    public DefaultRetryPolicy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    @Override
    public boolean shouldRetry(int retryCount, Throwable throwable) {
        return retryCount < maxRetries && isTransient(unwrap(throwable));
    }

    @Override
    public long retryDelayMillis(int retryCount, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof ModelRateLimitException) {
            Long retryAfter = ((ModelRateLimitException) cause).getRetryAfterMillis();
            // 避免供应商异常响应让调用线程无限期等待；更长退避可由自定义策略控制。
            return retryAfter == null ? 0L : Math.min(Math.max(0L, retryAfter), 30_000L);
        }
        return 0L;
    }

    /**
     * 默认只重试临时服务故障，不重复发送确定会失败的请求。
     */
    private boolean isTransient(Throwable throwable) {
        if (throwable == null) return false;
        if (throwable instanceof TokenLimitExceededException
            || throwable instanceof ModelQuotaExceededException) return false;
        if (throwable instanceof ModelRateLimitException
            || throwable instanceof ModelOverloadedException) return true;
        if (throwable instanceof IOException || throwable instanceof TimeoutException
            || throwable instanceof ConnectException || throwable instanceof SocketTimeoutException) {
            return true;
        }
        return !(throwable instanceof ModelException);
    }

    private Throwable unwrap(Throwable throwable) {
        while (throwable != null && (throwable instanceof java.util.concurrent.CompletionException
            || throwable instanceof java.util.concurrent.ExecutionException)
            && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }
}
