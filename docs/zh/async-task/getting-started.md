# 异步任务

`agents-flex-async-task` 用于持久化跟踪 OCR、视频等服务商异步任务。提交参数只在当前调用中使用；提交成功后，后续查询需要的 `TaskQueryParams`、状态、结果、重试计数和 Worker Lease 会保存到 Store。

## OCR 示例

```java
GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
OcrModel model = new GiteeOcrModel(config);

InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry();
registry.register(new OcrAsyncTaskHandler("ocr:gitee", model));

AsyncTaskStore store = new InMemoryAsyncTaskStore();
AsyncTaskManager manager = new AsyncTaskManager(store, registry);

AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1",
    store,
    registry,
    new ExponentialAsyncTaskRetryPolicy(3_000L, 1_000L, 60_000L, 5),
    30_000L
);
worker.start(1_000L, 10);

OcrRequest request = OcrRequest.ofUrl("https://example.com/document.pdf");
AsyncTask task = manager.submit("ocr:gitee", request, 30 * 60_000L);

AsyncTask latest = manager.get(task.getId());
if (latest.getStatus() == AsyncTaskStatus.SUCCEEDED) {
    OcrResponse response = (OcrResponse) latest.getResult();
    System.out.println(response.getMarkdown());
}
```

生产环境应提供 JDBC 或 Redis 等持久化 `AsyncTaskStore`。`InMemoryAsyncTaskStore` 仅用于单进程开发和测试。

提交调用发生不确定异常时，任务进入 `SUBMIT_UNKNOWN`。因为提交参数默认不持久化，框架不会自动重新提交，从而避免不支持幂等键的服务商产生重复收费任务。

自定义供应商若查询需要批任务类型、区域或查询 URL，可以继承 `OcrAsyncTaskHandler`，在 `createQueryParams()` 中保存这些参数，并在 `queryModel()` 中读取。

## 提交调度与准入控制

`submit()` 会在调用线程立即提交，不经过调度。需要供应商 QPS、账号并发、租户配额、优先级、延迟提交或供应商暂停时，应使用 `enqueue()`：

```java
InMemoryAsyncTaskAdmissionPolicy admission = new InMemoryAsyncTaskAdmissionPolicy();
admission.setProviderQps("gitee", 5);
admission.setAccountConcurrency("gitee", "account-a", 2);
admission.setTenantQuota("tenant-a", 10);
admission.pauseProvider("gitee");
admission.resumeProvider("gitee");

AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");
options.setAccountId("account-a");
options.setTenantId("tenant-a");
options.setPriority(100);          // 数字越大，优先级越高
options.setDelayMillis(30_000L);  // 30 秒后才允许提交

AsyncTask task = manager.enqueue(
    "ocr:gitee",
    serializableSubmitParams,
    30 * 60_000L,
    options
);

AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", store, registry, retryPolicy, admission, 30_000L
);
worker.start(1_000L, 10);
```

`enqueue()` 的提交参数必须实现 `Serializable`，生产 Store 还需要真正序列化该参数。建议保存对象存储 key、模型和选项等稳定 DTO，不要保存 API Key、HTTP Client、本地临时文件句柄或短期签名 URL。

`InMemoryAsyncTaskAdmissionPolicy` 和 `InMemoryAsyncTaskStore` 只提供单 JVM 语义。集群部署若要求精确的全局 QPS、账号并发和租户配额，持久化 Store 必须在领取事务中使用共享计数/时间窗口实现同等的原子语义，或注入基于 Redis 等共享基础设施的 `AsyncTaskAdmissionPolicy`。
