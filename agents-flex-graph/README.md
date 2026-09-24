# Agents-Flex Graph

`agents-flex-graph-api` defines a portable property-graph contract for graph-space management, schema application, node/edge upserts, streaming imports, traversal queries, native queries and transactions.

The API deliberately separates portable operations from backend-specific escape hatches. Every backend declares its `GraphCapabilities`; unsupported operations fail explicitly instead of silently changing query semantics.

`agents-flex-graph-extractor` adds a reviewable document-to-graph pipeline on top of the API. It uses the existing `DocumentSplitter` and `ChatModel`, validates candidates against `GraphSchema`, resolves aliases across chunks, and produces a `GraphMutation` without writing it automatically.

`agents-flex-graph-testkit` publishes reusable JUnit contracts for document-state stores, recoverable operation stores, and ingestion lock providers. `agents-flex-graph-integration-tests` is a non-deployable reactor module that verifies the complete extraction, recovery, write, query, and retraction lifecycle against real Neo4j and Nebula instances.

测试还覆盖默认分页游标的查询指纹绑定、过滤值快照、异步服务关闭后的线程回收、不可用端点探活和同键并发 upsert；
Nebula writer 会对 Storage 返回的并发冲突执行有限退避重试。

## Example

```java
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.neo4j.Neo4jGraphStore;

GraphNode alice = GraphNode.builder("u-1", "Person")
    .property("name", "Alice")
    .build();

TraversalQuery query = TraversalQuery.from(NodePattern.node("person", "Person"))
    .traverse(EdgePattern.edge("knows", "KNOWS", Direction.OUT), NodePattern.node("friend", "Person"))
    .where(GraphFilter.eq("person", "tenant", "tenant-a"))
    .select(Projection.entity("friend"))
    .limit(20)
    .build();

try (GraphStore graph = new Neo4jGraphStore(config)) {
    graph.writer().upsert(alice, GraphOptions.DEFAULT);
    GraphResult result = graph.query().execute(query, GraphOptions.DEFAULT);
}
```

The API package is organized by capability: `data`, `query`, `mutation`, `importing`,
`manager`, `schema`, `connection`, `capability`, `transaction`, and `identifier`.
Only the `GraphStore` facade, shared `GraphOptions`, and common exceptions remain in
the `com.agentsflex.graph` root package.

Neo4j uses the official Java Driver. Nebula Graph uses the official SessionPool client and binds each operation to the selected graph space. Database servers, credentials, TLS and production schema migration remain deployment concerns of the host application.
