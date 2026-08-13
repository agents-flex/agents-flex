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
package com.agentsflex.asynctask.store.redis;

import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.*;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 使用 Redis Hash、调度 ZSet 与 Lua 脚本实现的异步任务 Store。
 *
 * <p>所有键包含相同 hash tag，可在 Redis Cluster 的同一槽位执行 Lua。创建、CAS、领取、续租、
 * 释放和取消均由服务端脚本一次完成，多个 Worker 不会同时获得同一 leaseId。</p>
 *
 * <p>AdmissionPolicy 仍是本地 Java 扩展点：脚本负责任务领取原子性，跨 JVM 的供应商 QPS、账号并发
 * 和租户配额应由共享策略实现。候选列表只读取到本进程，任务正文不会在 Lua 中反序列化。</p>
 */
public final class RedisAsyncTaskStore implements AsyncTaskStore {
    // 服务端脚本一次完成版本/租约校验、状态转换和索引更新，消除多次 Redis 往返的竞争窗口。
    private static final String CLAIM = "local v=redis.call('HGET',KEYS[1],'version'); if not v or tonumber(v)~=tonumber(ARGV[1]) then return 0 end; "
        + "local lu=tonumber(redis.call('HGET',KEYS[1],'lease_until') or '0'); if lu>tonumber(ARGV[2]) then return 0 end; "
        + "redis.call('HSET',KEYS[1],'version',ARGV[3],'status',ARGV[4],'lease_owner',ARGV[5],'lease_id',ARGV[6],'lease_until',ARGV[7],'payload',ARGV[8]); "
        // 以 leaseUntil 保留在原到期索引，Worker 崩溃后任务会在租约到期时重新可见。
        + "redis.call('ZADD',KEYS[2],ARGV[7],ARGV[9]); redis.call('ZADD',KEYS[3],ARGV[7],ARGV[9]); return 1";
    private final RedisAsyncTaskStoreConfig config;
    private final JedisPooled jedis;

    RedisAsyncTaskStore(RedisAsyncTaskStoreConfig config) {
        this.config = config;
        this.jedis = config.jedis();
    }

    @Override
    public long currentTimeMillis() {
        Object value = eval("local t=redis.call('TIME'); return t[1]*1000+math.floor(t[2]/1000)", Collections.<String>emptyList(), Collections.<String>emptyList());
        return num(value);
    }

    @Override
    public AsyncTask create(AsyncTask task) {
        requireTask(task);
        AsyncTask value = task.copy();
        value.setVersion(0);
        String script = "if redis.call('EXISTS',KEYS[1])==1 then return 0 end; redis.call('HSET',KEYS[1],'version','0','status',ARGV[1],'lease_owner',ARGV[2],'lease_id',ARGV[3],'lease_until',ARGV[4],'cancel',ARGV[5],'payload',ARGV[6]); redis.call('SADD',KEYS[2],ARGV[7]); if ARGV[1]=='PENDING_SUBMIT' then redis.call('ZADD',KEYS[3],ARGV[8],ARGV[7]) elseif ARGV[1]=='SUBMITTED' or ARGV[1]=='RUNNING' then redis.call('ZADD',KEYS[4],ARGV[9],ARGV[7]) end; return 1";
        long code = num(eval(script, keys(taskKey(value.getId()), allKey(), submitKey(), queryKey()), args(value.getStatus().name(), text(value.getLeaseOwner()), text(value.getLeaseId()), String.valueOf(value.getLeaseUntil()), value.isCancellationRequested() ? "1" : "0", encode(value), value.getId(), String.valueOf(value.getScheduledSubmitAt()), String.valueOf(value.getNextQueryAt()))));
        if (code != 1) throw new IllegalStateException("Async task already exists: " + value.getId());
        return value.copy();
    }

    @Override
    public AsyncTask load(String id) {
        if (id == null) return null;
        Map<String, String> h = jedis.hgetAll(taskKey(id));
        if (h.isEmpty()) return null;
        AsyncTask t = decode(h.get("payload"));
        // Lua 会单独更新这些轻量字段，因此 Hash 投影比 payload 中的同名字段更权威。
        t.setVersion(number(h.get("version")));
        t.setLeaseOwner(empty(h.get("lease_owner")));
        t.setLeaseId(empty(h.get("lease_id")));
        t.setLeaseUntil(number(h.get("lease_until")));
        t.setCancellationRequested("1".equals(h.get("cancel")));
        return t;
    }

    @Override
    public AsyncTask save(AsyncTask task, long expected) {
        requireTask(task);
        AsyncTask current = load(task.getId());
        if (current == null) throw new IllegalStateException("Async task does not exist: " + task.getId());
        long now = currentTimeMillis();
        if (current.getLeaseId() != null && current.getLeaseUntil() <= now)
            throw new IllegalStateException("Async task lease has expired: " + task.getId());
        if (current.getLeaseId() != null && (!eq(current.getLeaseId(), task.getLeaseId()) || !eq(current.getLeaseOwner(), task.getLeaseOwner())))
            throw new IllegalStateException("Async task lease does not match: " + task.getId());
        AsyncTask value = task.copy();
        value.setVersion(expected + 1);
        value.setCancellationRequested(current.isCancellationRequested() || task.isCancellationRequested());
        String due = value.getStatus().isPendingSubmission() ? String.valueOf(value.getScheduledSubmitAt()) : String.valueOf(value.getNextQueryAt());
        String script = "local v=redis.call('HGET',KEYS[1],'version'); if not v then return -1 end; if tonumber(v)~=tonumber(ARGV[1]) then return tonumber(v) end; local c=redis.call('HGET',KEYS[1],'cancel'); redis.call('HSET',KEYS[1],'version',ARGV[2],'status',ARGV[3],'lease_owner',ARGV[4],'lease_id',ARGV[5],'lease_until',ARGV[6],'cancel',(c=='1' and '1' or ARGV[7]),'payload',ARGV[8]); redis.call('ZREM',KEYS[2],ARGV[9]); redis.call('ZREM',KEYS[3],ARGV[9]); if ARGV[3]=='PENDING_SUBMIT' then redis.call('ZADD',KEYS[2],ARGV[10],ARGV[9]) elseif ARGV[3]=='SUBMITTED' or ARGV[3]=='RUNNING' then redis.call('ZADD',KEYS[3],ARGV[10],ARGV[9]) end; return -2";
        long code = num(eval(script, keys(taskKey(value.getId()), submitKey(), queryKey()), args(String.valueOf(expected), String.valueOf(value.getVersion()), value.getStatus().name(), text(value.getLeaseOwner()), text(value.getLeaseId()), String.valueOf(value.getLeaseUntil()), value.isCancellationRequested() ? "1" : "0", encode(value), value.getId(), due)));
        if (code != -2) throw new AsyncTaskVersionConflictException(value.getId(), expected, code);
        return load(value.getId());
    }

    @Override
    public List<AsyncTask> claimDueSubmissions(String worker, long now, long lease, int limit, AsyncTaskAdmissionPolicy policy) {
        validate(worker, lease, limit);
        if (policy == null) throw new IllegalArgumentException("admissionPolicy is required");
        List<AsyncTask> snapshot = all();
        List<AsyncTask> candidates = due(submitKey(), now, limit * 8);
        // ZSet 负责到期筛选，本地再按业务优先级稳定排序；最终唯一领取仍由 Lua CAS 保证。
        candidates.sort(Comparator.comparingInt(AsyncTask::getPriority).reversed().thenComparingLong(AsyncTask::getScheduledSubmitAt).thenComparingLong(AsyncTask::getCreatedAt));
        List<AsyncTask> out = new ArrayList<>();
        for (AsyncTask t : candidates) {
            if (out.size() >= limit) break;
            if (policy.tryAcquire(t.copy(), snapshot, now)) {
                AsyncTask c = claim(t, worker, now, lease, true, submitKey());
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    @Override
    public List<AsyncTask> claimDueTasks(String worker, long now, long lease, int limit) {
        validate(worker, lease, limit);
        List<AsyncTask> out = new ArrayList<>();
        for (AsyncTask t : due(queryKey(), now, limit * 4)) {
            if (out.size() >= limit) break;
            AsyncTask c = claim(t, worker, now, lease, false, queryKey());
            if (c != null) out.add(c);
        }
        return out;
    }

    @Override
    public AsyncTask renewLease(String id, String worker, String leaseId, long now, long until) {
        if (until <= now) throw new IllegalArgumentException("leaseUntil must be after now");
        String s = "if redis.call('HGET',KEYS[1],'lease_owner')~=ARGV[1] or redis.call('HGET',KEYS[1],'lease_id')~=ARGV[2] or tonumber(redis.call('HGET',KEYS[1],'lease_until') or '0')<=tonumber(ARGV[3]) then return 0 end; redis.call('HSET',KEYS[1],'lease_until',ARGV[4]); redis.call('ZADD',KEYS[2],ARGV[4],ARGV[5]); return 1";
        if (num(eval(s, keys(taskKey(id), leasedKey()), args(worker, leaseId, String.valueOf(now), String.valueOf(until), id))) != 1)
            throw new IllegalStateException("Async task lease is not owned by worker: " + worker);
        return load(id);
    }

    @Override
    public void releaseLease(String id, String worker, String leaseId) {
        String s = "if redis.call('HGET',KEYS[1],'lease_owner')==ARGV[1] and redis.call('HGET',KEYS[1],'lease_id')==ARGV[2] then redis.call('HSET',KEYS[1],'lease_owner','','lease_id','','lease_until','0'); redis.call('ZREM',KEYS[2],ARGV[3]); return 1 end; return 0";
        eval(s, keys(taskKey(id), leasedKey()), args(worker, leaseId, id));
    }

    @Override
    public boolean requestCancellation(String id) {
        String s = "local st=redis.call('HGET',KEYS[1],'status'); if not st or redis.call('HGET',KEYS[1],'cancel')=='1' or st=='SUCCEEDED' or st=='FAILED' or st=='CANCELED' or st=='TRACKING_TIMED_OUT' or st=='SUBMIT_UNKNOWN' then return 0 end; redis.call('HSET',KEYS[1],'cancel','1','version',tonumber(redis.call('HGET',KEYS[1],'version'))+1); return 1";
        return num(eval(s, keys(taskKey(id)), Collections.<String>emptyList())) == 1;
    }

    private AsyncTask claim(AsyncTask t, String worker, long now, long lease, boolean submitting, String dueKey) {
        AsyncTask c = t.copy();
        c.setVersion(t.getVersion() + 1);
        c.setLeaseOwner(worker);
        c.setLeaseId(UUID.randomUUID().toString());
        c.setLeaseUntil(now + lease);
        if (submitting) c.setStatus(AsyncTaskStatus.SUBMITTING);
        long ok = num(eval(CLAIM, keys(taskKey(t.getId()), dueKey, leasedKey()), args(String.valueOf(t.getVersion()), String.valueOf(now), String.valueOf(c.getVersion()), c.getStatus().name(), worker, c.getLeaseId(), String.valueOf(c.getLeaseUntil()), encode(c), c.getId())));
        return ok == 1 ? c : null;
    }

    private List<AsyncTask> due(String key, long now, int count) {
        List<String> ids = jedis.zrangeByScore(key, Double.NEGATIVE_INFINITY, now, 0, count);
        List<AsyncTask> out = new ArrayList<>();
        for (String id : ids) {
            AsyncTask t = load(id);
            if (t != null && t.getLeaseUntil() <= now) out.add(t);
        }
        return out;
    }

    private List<AsyncTask> all() {
        List<AsyncTask> out = new ArrayList<>();
        for (String id : jedis.smembers(allKey())) {
            AsyncTask t = load(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    private String taskKey(String id) {
        return config.keyPrefix() + "{async-task}:task:" + id;
    }

    private String allKey() {
        return config.keyPrefix() + "{async-task}:tasks";
    }

    private String submitKey() {
        return config.keyPrefix() + "{async-task}:due-submit";
    }

    private String queryKey() {
        return config.keyPrefix() + "{async-task}:due-query";
    }

    private String leasedKey() {
        return config.keyPrefix() + "{async-task}:leased";
    }

    private Object eval(String s, List<String> k, List<String> a) {
        return jedis.eval(s, k, a);
    }

    private String encode(AsyncTask t) {
        return Base64.getEncoder().encodeToString(config.serializer().serialize(t));
    }

    private AsyncTask decode(String s) {
        return config.serializer().deserialize(Base64.getDecoder().decode(s.getBytes(StandardCharsets.US_ASCII)), AsyncTask.class);
    }

    private static List<String> keys(String... v) {
        return Arrays.asList(v);
    }

    private static List<String> args(String... v) {
        return Arrays.asList(v);
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String empty(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static long number(String v) {
        return v == null || v.isEmpty() ? 0 : Long.parseLong(v);
    }

    private static long num(Object v) {
        if (v instanceof byte[]) return Long.parseLong(new String((byte[]) v, StandardCharsets.US_ASCII));
        return Long.parseLong(String.valueOf(v));
    }

    private void requireTask(AsyncTask t) {
        if (t == null || t.getId() == null || t.getStatus() == null)
            throw new IllegalArgumentException("task, task.id and task.status are required");
    }

    private void validate(String w, long l, int n) {
        if (w == null || l <= 0 || n <= 0) throw new IllegalArgumentException("Invalid claim arguments");
    }

    private boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
