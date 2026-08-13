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
import org.junit.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/**
 * 在真实 Redis 上验证 Lua 脚本原子性和任务快照恢复。
 *
 * <p>本地没有 Redis 时测试会跳过；CI 可以通过 {@code redis.test.uri} 指向测试实例。
 * 每个用例使用随机键前缀并在结束时清理，不会碰触其他应用数据。</p>
 */
public class RedisAsyncTaskStoreIntegrationTest {
    private RedisAsyncTaskStoreConfig config;
    private RedisAsyncTaskStore store;
    private String prefix;

    /** 建立隔离键空间，并在 Redis 不可用时明确跳过集成测试。 */
    @Before public void setUp() {
        prefix="agents-flex-async-it:"+UUID.randomUUID()+":";
        config=RedisAsyncTaskStoreConfig.builder(System.getProperty("redis.test.uri","redis://127.0.0.1:6379")).keyPrefix(prefix).build();
        try { config.jedis().ping(); store=config.store(); }
        catch(RuntimeException error){config.close();config=null;Assume.assumeNoException("Redis is required for async task integration tests",error);}
    }

    /** 删除本测试随机前缀下的键，保证测试可重复运行。 */
    @After public void tearDown(){if(config==null)return;String cursor="0";do{ScanResult<String> scan=config.jedis().scan(cursor,new ScanParams().match(prefix+"*").count(100));cursor=scan.getCursor();if(!scan.getResult().isEmpty())config.jedis().del(scan.getResult().toArray(new String[0]));}while(!"0".equals(cursor));config.close();}

    /** 验证创建、CAS、查询领取、续租、释放和取消的完整 Redis 生命周期。 */
    @Test public void shouldPersistCompleteLifecycle(){AsyncTask t=task("task",AsyncTaskStatus.RUNNING,0);assertEquals(0,store.create(t).getVersion());AsyncTask loaded=store.load("task");loaded.setProviderStatus("processing");assertEquals(1,store.save(loaded,0).getVersion());AsyncTask claimed=store.claimDueTasks("worker",10,100,1).get(0);assertEquals("worker",claimed.getLeaseOwner());assertEquals(300,store.renewLease("task","worker",claimed.getLeaseId(),20,300).getLeaseUntil());store.releaseLease("task","worker",claimed.getLeaseId());assertNull(store.load("task").getLeaseOwner());assertTrue(store.requestCancellation("task"));assertFalse(store.requestCancellation("task"));}

    /** 验证优先级排序和延迟提交，并确保被策略拒绝的任务仍留在待提交索引。 */
    @Test public void shouldScheduleSubmissionByPriorityAndPolicy(){AsyncTask low=task("low",AsyncTaskStatus.PENDING_SUBMIT,0);low.setPriority(1);AsyncTask high=task("high",AsyncTaskStatus.PENDING_SUBMIT,0);high.setPriority(9);AsyncTask future=task("future",AsyncTaskStatus.PENDING_SUBMIT,1000);store.create(low);store.create(high);store.create(future);List<AsyncTask> values=store.claimDueSubmissions("w",100,100,10,(candidate,all,now)->!"low".equals(candidate.getId()));assertEquals(1,values.size());assertEquals("high",values.get(0).getId());assertEquals(1,store.claimDueSubmissions("w2",100,100,10,(candidate,all,now)->true).size());}

    /** 两个客户端并发执行 Lua 领取脚本时，只能有一个获得同一任务。 */
    @Test public void shouldAllowOnlyOneConcurrentClaim() throws Exception {store.create(task("race",AsyncTaskStatus.RUNNING,0));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{Future<List<AsyncTask>> a=pool.submit(()->{start.await();return store.claimDueTasks("a",10,100,1);});Future<List<AsyncTask>> b=pool.submit(()->{start.await();return store.claimDueTasks("b",10,100,1);});start.countDown();assertEquals(1,a.get().size()+b.get().size());}finally{pool.shutdownNow();}}

    /** 模拟 Worker 领取后直接崩溃；租约到期后任务必须仍可被其他 Worker 发现并重新领取。 */
    @Test public void shouldRecoverTaskAfterWorkerLeaseExpires(){store.create(task("recover",AsyncTaskStatus.RUNNING,0));AsyncTask first=store.claimDueTasks("dead-worker",10,20,1).get(0);assertTrue(store.claimDueTasks("early",29,20,1).isEmpty());AsyncTask recovered=store.claimDueTasks("new-worker",30,20,1).get(0);assertNotEquals(first.getLeaseId(),recovered.getLeaseId());assertEquals("new-worker",recovered.getLeaseOwner());}

    private AsyncTask task(String id,AsyncTaskStatus status,long due){AsyncTask t=new AsyncTask();t.setId(id);t.setHandlerKey("test");t.setProviderKey("provider");t.setStatus(status);t.setCreatedAt(1);t.setScheduledSubmitAt(due);t.setNextQueryAt(due);return t;}
}
