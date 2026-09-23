# GraphStore 门面

GraphStore 是 Graph SDK 的组合入口，不是所有业务逻辑的唯一承载点。它适合在应用边界注入，但内部服务
应按职责依赖更小的接口。

## 入口方法

| 需求 | API |
| --- | --- |
| 检查后端能力 | capabilities() |
| 创建空间、应用 Schema | manager() |
| 节点和边写入 | writer() |
| Portable / Native 查询 | query() |
| 显式事务 | transactions() |
| 异步导入 | imports() |
| 连接探活 | health() |

查询服务可以只依赖 GraphQueryExecutor，Schema 管理服务可以只依赖 GraphManager，避免所有模块都依赖整个
Store，也更容易替换测试实现。

## 生命周期

Store 通常由应用启动时创建、应用关闭时释放：

~~~java
try (GraphStore store = createStore()) {
    runApplicationOperations(store);
}
~~~

Store 关闭后不应继续提交查询、写入或导入任务。Nebula 适配器会禁止关闭后隐式重建 SessionPool；调用方应将
Store 生命周期与连接配置生命周期绑定。

## 默认空间与单次路由

配置中的 default space 用于没有显式指定空间的操作。需要访问其他空间时：

~~~java
GraphOptions options = GraphOptions.ofSpace("tenant_graph");
store.query().execute(query, options);
~~~

写入、查询、删除和导入必须使用一致的空间路由，否则可能出现“写入成功但查询不到”。

