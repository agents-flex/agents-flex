/**
 * 图谱抽取流程的 JDBC 持久化实现。
 *
 * <p>本包实现文档状态、摄取操作、人工审核、异步导入、实体注册表以及跨进程摄取锁等扩展点。
 * 结构化查询字段存入普通关系列，不可变领域快照则通过可替换的序列化器存入二进制列。</p>
 *
 * <p>内置 DDL 以 MySQL 和 H2 MySQL 模式为兼容基线。生产环境建议由 Flyway、Liquibase 等迁移工具
 * 管理等价表结构，并在应用启动时通过 {@link com.agentsflex.graph.store.jdbc.JdbcGraphStoreConfig}
 * 统一创建各 Store 实例。</p>
 */
package com.agentsflex.graph.store.jdbc;
