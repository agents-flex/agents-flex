# 自定义 AsyncTaskHandler

`AsyncTaskHandler<P>` 把任意供应商的异步协议适配为两个原子操作：创建一次远端任务，以及查询一次远端状态。循环、休眠、退避、截止时间和持久化由 Worker 与 Store 负责。

## 什么时候需要自定义 Handler

- 接入 OCR、视频之外的异步供应商。
- 需要为自定义请求类型定义持久化校验和供应商提交逻辑。
- 查询除了任务 ID，还需要区域、批次类型、账号路由或动态游标。
- 内置 OCR Handler 无法恢复某种供应商的特殊查询路由，例如 MinerU 本地文件批任务。

## 接口职责

```java
public interface AsyncTaskHandler<P> {
    String getKey();
    // 已有默认实现：根据实现类的泛型声明解析并缓存 P。
    Class<P> getSubmitParamsType();
    default void validateSubmitParams(P params) {}
    TaskSubmitResult submit(P params, TaskSubmitContext context);
    TaskQueryResult query(TaskQueryParams params, TaskQueryContext context);
}
```

`query()` 只执行一次请求，不应在 Handler 内循环或 `Thread.sleep()`。

## 开发步骤

1. 定义实现 `Serializable` 的稳定提交参数类型。
2. 在 `validateSubmitParams()` 中拒绝本地文件、流、二进制和其他无法跨节点恢复的数据。
3. 在 `submit()` 中创建一次供应商任务，并返回可持久化查询参数。
4. 在 `query()` 中只查询一次并映射统一状态。
5. 使用稳定的 Handler Key 注册，并在所有 Worker 实例中保持一致。
6. 分别测试参数拒绝、提交成功、同步完成、供应商失败、查询重试和游标更新。

## `submit()`

`submit()` 接收业务 payload 和框架上下文，用于调用供应商创建任务：

```java
@Override
public TaskSubmitResult submit(DocumentParseRequest params,
                               TaskSubmitContext context) {
    ProviderSubmitResponse response = client.createTask(
        params,
        context.getIdempotencyKey()
    );

    TaskSubmitResult result = new TaskSubmitResult();
    result.setStatus(AsyncTaskStatus.SUBMITTED);

    TaskQueryParams queryParams =
        new TaskQueryParams(response.getTaskId());
    queryParams.putProviderParam("region", response.getRegion());
    result.setQueryParams(queryParams);
    return result;
}
```

返回 `SUBMITTED` 或 `RUNNING` 时，必须提供带有效 `externalTaskId` 的 `TaskQueryParams`。供应商同步完成时，可以直接返回 `SUCCEEDED` 和 `result`。

`TaskSubmitContext` 提供：

| 字段 | 说明 |
| --- | --- |
| `taskId` | 框架内部任务 ID |
| `idempotencyKey` | 默认与 taskId 相同，应传给支持幂等的供应商 |
| `currentTimeMillis` | Store 权威时间 |
| `metadata` | 业务扩展信息，不包含 payload |

## `TaskQueryParams`

只传一个供应商任务 ID 对简单接口足够，但区域、账号路由、批次类型或动态查询端点也可能是查询所必需的。将这些稳定字段放入 `providerParams`：

```java
TaskQueryParams params = new TaskQueryParams(externalTaskId);
params.putProviderParam("region", "cn-beijing");
params.putProviderParam("batch", true);
```

`TaskQueryParams` 会持久化。不要放 HTTP Client、凭证、打开的流或短期对象引用。

## `query()`

```java
@Override
public TaskQueryResult query(TaskQueryParams params,
                             TaskQueryContext context) {
    String region = (String) params.getProviderParams().get("region");
    ProviderTask task = client.getTask(
        region,
        params.getExternalTaskId()
    );

    TaskQueryResult result = new TaskQueryResult();
    result.setProviderStatus(task.getStatus());

    if (task.isSucceeded()) {
        result.setStatus(AsyncTaskStatus.SUCCEEDED);
        result.setResult(task.getOutput());
    } else if (task.isFailed()) {
        result.setStatus(AsyncTaskStatus.FAILED);
        result.setErrorCode(task.getErrorCode());
        result.setErrorMessage(task.getErrorMessage());
    } else {
        result.setStatus(AsyncTaskStatus.RUNNING);
    }
    return result;
}
```

`TaskQueryContext` 是本轮运行上下文，不包含 `TaskQueryParams`：

| 字段 | 说明 |
| --- | --- |
| `taskId` | 框架任务 ID |
| `queryCount` | 本次调用前已完成的查询次数 |
| `consecutiveErrors` | 本次调用前连续查询异常数 |
| `createdAt` / `deadlineAt` | 任务时间边界 |
| `currentTimeMillis` | Store 权威时间 |
| `metadata` | 只读业务信息 |

供应商在查询过程中更换游标、查询 token 或端点时，可以返回 `nextQueryParams`。Worker 会持久化它供下一轮使用；为空则继续使用原参数。

## 注册 Handler

```java
public final class MyAsyncTaskHandler
    implements AsyncTaskHandler<DocumentParseRequest> {

    @Override
    public String getKey() {
        return "document:my-provider";
    }

    // submit() 和 query() 省略
}

registry.register(new MyAsyncTaskHandler());
```

键必须稳定且唯一。所有处理历史任务的 Worker 都必须注册同一版本兼容的 Handler。框架默认从
`AsyncTaskHandler<DocumentParseRequest>` 的泛型声明解析精确类型；直接实现接口、泛型抽象基类和多层泛型
继承通常都不需要覆盖 `getSubmitParamsType()`。Manager 按解析出的精确类型寻找候选，单一候选直接使用，
不要求业务代码再次传 key。

如果 Handler 使用了运行时仍未绑定的类型变量，或者代理框架没有保留泛型签名，注册时会立即失败并提示
显式覆盖类型方法。此时可以把具体类型写清楚：

```java
@Override
public Class<DocumentParseRequest> getSubmitParamsType() {
    return DocumentParseRequest.class;
}
```

框架不会在无法解析时退化成 `Object.class`，从而避免无关 Handler 被错误匹配。

同一个参数类型注册多个 Handler 时，在 Manager 构造器中配置 `AsyncTaskHandlerSelector`。也可以通过
`AsyncTaskOptions.handlerKey` 为某一次提交强制路由；显式 key 优先于 selector。无论采用哪种方式，选择
结果都会持久化到任务，Worker 不会在后续处理或恢复时重新选择。

## 扩展内置 OCR Handler

简单 OCR 模型可以直接使用 `OcrAsyncTaskHandler`。供应商查询还需要批次或区域时，可覆盖两个扩展点：

```java
public final class RegionalOcrHandler extends OcrAsyncTaskHandler {
    public RegionalOcrHandler(String key, OcrModel model) {
        super(key, model);
    }

    @Override
    protected TaskQueryParams createQueryParams(OcrResponse response,
                                                OcrRequest request) {
        TaskQueryParams params =
            new TaskQueryParams(response.getTaskId());
        params.putProviderParam("region", "cn-beijing");
        return params;
    }

    @Override
    protected OcrResponse queryModel(TaskQueryParams params,
                                     TaskQueryContext context) {
        String region =
            (String) params.getProviderParams().get("region");
        return queryRegionalModel(region, params.getExternalTaskId());
    }
}
```

`createQueryParams()` 和 `queryModel()` 的字段约定必须向后兼容，否则 Store 中的历史任务可能无法恢复。

## 实现约束

- `submit()` 返回 `SUBMITTED` 或 `RUNNING` 时必须包含有效查询参数。
- `submit()` 抛出异常后 Worker 会把任务记为 `SUBMIT_UNKNOWN`，不会使用查询重试策略自动重提。
- `query()` 只能返回可继续查询状态或终态，不能返回 `PENDING_SUBMIT`、`SUBMITTING`。
- 查询异常应抛出运行时异常交给 RetryPolicy，不要在 Handler 内自行无限重试。
- `result`、metadata 和 `providerParams` 必须能被 Store 序列化。
- 不要把 API Key 放入任务持久化字段；凭证应由运行时配置或账号路由加载。
- 供应商支持幂等键时，应传递 `context.getIdempotencyKey()`。

## 测试建议

自定义 Handler 至少应覆盖：

| 场景 | 预期 |
| --- | --- |
| 本地文件、流或二进制输入 | 在写入 Store 前抛出明确异常并提示改用 URL |
| 提交返回任务 ID | 生成 `TaskQueryParams` |
| 供应商同步完成 | 直接返回 `SUCCEEDED` 和结果 |
| 查询仍在运行 | 返回 `RUNNING`，不在 Handler 内循环 |
| 查询成功或失败 | 正确映射终态、结果和错误信息 |
| 查询参数发生变化 | 使用 `nextQueryParams` 保存下一轮游标 |
| 提交网络异常 | 抛出异常，任务进入 `SUBMIT_UNKNOWN`，不得假设可以安全重提 |
| 查询网络异常 | 抛出异常，由 Worker 按 RetryPolicy 退避重试 |
| 历史任务恢复 | 旧版 DTO 和 providerParams 仍可反序列化 |

## 下一步

- [异步任务概览](./overview)
- [调度与准入控制](./scheduling)
- [Store 持久化](./store)
