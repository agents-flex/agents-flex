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
package com.agentsflex.util;

import java.util.concurrent.*;

/**
 * @author michael yang (fuhai999@gmail.com)
 */
public class NamedThreadPools {

    public static ExecutorService newFixedThreadPool(String prefix) {
        int nThreads = Runtime.getRuntime().availableProcessors();
        return newFixedThreadPool(nThreads, prefix);
    }


    public static ExecutorService newFixedThreadPool(int nThreads, String name) {
        return Executors.newFixedThreadPool(nThreads, new NamedThreadFactory(name));
    }


    public static ExecutorService newCachedThreadPool(String name) {
        return newCachedThreadPool(new NamedThreadFactory(name));
    }


    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                threadFactory);
    }


    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, String name) {
        return newScheduledThreadPool(corePoolSize, new NamedThreadFactory(name));
    }


    public static ScheduledExecutorService newScheduledThreadPool(
            int corePoolSize, ThreadFactory threadFactory) {
        return new ScheduledThreadPoolExecutor(corePoolSize, threadFactory);
    }
}
