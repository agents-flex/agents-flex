# 过滤条件

GraphFilter 用于构建可移植属性条件，值会被编译为参数。

## 比较条件

~~~java
GraphFilter filter = GraphFilter.and(
    GraphFilter.eq("person", "tenant", "tenant-a"),
    GraphFilter.ge("person", "age", 18L),
    GraphFilter.in("person", "status", Arrays.asList("ACTIVE", "TRIAL")));
~~~

支持 EQ、NE、GT、GE、LT、LE、IN、NOT_IN、BETWEEN、IS_NULL 和 IS_NOT_NULL。

## 逻辑组合

~~~java
GraphFilter filter = GraphFilter.or(
    GraphFilter.eq("person", "role", "admin"),
    GraphFilter.not(GraphFilter.eq("person", "disabled", true)));
~~~

AND、OR、NOT 会保留组合结构，由适配器生成目标方言表达式。

## 后端注意事项

Nebula MATCH 属性过滤需要可用 TAG/EDGE index。Schema 应先创建和重建索引，再执行属性过滤。
Neo4j 通常可以执行没有对应索引的过滤，但生产查询仍应按执行计划建立合适索引。

属性过滤不能替代租户授权。应用应在服务边界强制注入租户条件，不能要求调用者自行记住添加 filter。
