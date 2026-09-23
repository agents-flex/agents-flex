# 能力声明

GraphCapabilities 用于描述一个后端实例实际支持的 GraphFeature。能力矩阵是运行时信息，应用不应只根据
数据库名称猜测功能。

~~~java
GraphCapabilities capabilities = graph.capabilities();
if (!capabilities.supports(GraphFeature.TRANSACTIONS)) {
    // 隐藏事务入口，或切换到应用层补偿策略。
}
GraphCapabilityDetail detail = capabilities.describe(GraphFeature.BULK_IMPORT);
~~~

## 能力信息

能力详情可以包含支持状态、后端差异说明、参数化限制、支持模式和使用建议。

## 常见能力

| 能力 | 用途 |
| --- | --- |
| CREATE_SPACE / DROP_SPACE | 创建和删除逻辑空间 |
| SCHEMA / SCHEMA_INTROSPECTION | 应用和反查 Schema |
| INDEX / UNIQUE_CONSTRAINT | 索引和唯一约束 |
| TRANSACTIONS | 显式事务 |
| VARIABLE_LENGTH_PATH | 变长路径 |
| BULK_IMPORT | 批量导入 |
| QUERY_EXPLAIN | 执行计划 |
| STREAMING_CURSOR | 原生结果流 |
| NATIVE_QUERY | 原生方言查询 |

## 不支持能力的处理

不支持的能力应显式抛出 UnsupportedGraphFeatureException。调用方不要捕获异常后静默改成全图扫描，因为这
可能改变权限、性能和结果语义。

## 能力检查建议

在产品控制面中，可以将能力矩阵映射为可用/不可用的操作按钮、Schema 迁移警告、查询编辑器中的方言提示、
事务和分页能力说明，以及生产部署检查项。

