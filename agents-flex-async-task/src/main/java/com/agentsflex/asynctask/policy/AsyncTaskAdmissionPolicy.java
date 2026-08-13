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
package com.agentsflex.asynctask.policy;

import com.agentsflex.asynctask.AsyncTask;


import java.util.Collection;

/**
 * 待提交任务的准入策略，用于供应商 QPS、账号并发、租户配额和暂停控制。
 *
 * <p>内存 Store 会在领取锁内调用；外部 Store 保证任务本身的领取原子性，但任意 Java 策略无法
 * 自动下推到 SQL/Lua。多进程严格限流时，该实现自身必须使用 Redis、数据库或分布式限流服务。</p>
 */
public interface AsyncTaskAdmissionPolicy {
    /**
     * 尝试占用一次准入额度；返回 false 时任务保留在待提交队列。
     *
     * <p>只有返回 {@code true} 才能消耗 QPS 等一次性额度。实现应避免在拒绝租户或账号配额后
     * 提前消耗 QPS。Store 可能因 CAS 竞争导致领取失败，自定义分布式策略需要考虑额度补偿或短窗口过期。</p>
     *
     * @param task     当前候选任务的防御性副本
     * @param allTasks Store 在本轮领取前读取的任务快照，用于统计进程内活跃数量
     * @param now      Store 权威时钟的毫秒时间戳
     * @return {@code true} 表示允许本轮提交，{@code false} 表示保留等待
     */
    boolean tryAcquire(AsyncTask task, Collection<AsyncTask> allTasks, long now);
}
