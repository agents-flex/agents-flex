# Union 查询

GraphUnionQuery 将多个独立 TraversalQuery 组合为 UNION 或 UNION ALL。

~~~java
GraphUnionQuery query = GraphUnionQuery.union(
    activePeople,
    trialPeople);

GraphResult result = graph.query().execute(query, options);
~~~

union 会去重，unionAll 会保留重复记录。所有分支必须拥有相同数量、相同名称和兼容语义的投影。

~~~java
GraphUnionQuery all = GraphUnionQuery.unionAll(first, second);
~~~

适配器会为不同分支的参数重命名，避免 p0、p1 等参数冲突。分支的排序、limit 和后端 UNION 语义可能不同，
需要在目标数据库上执行验证。

Union 不适合代替复杂权限条件。租户条件、删除状态和访问范围应在每个分支中显式加入。

