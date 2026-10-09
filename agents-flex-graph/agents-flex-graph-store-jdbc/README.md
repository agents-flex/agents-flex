# Agents-Flex Graph JDBC Stores

This module provides JDBC-backed implementations for the graph extraction persistence extension points:

- `JdbcGraphDocumentStateStore`
- `JdbcGraphIngestionOperationStore`
- `JdbcGraphReviewStore`
- `JdbcGraphImportTaskStore`
- `JdbcGraphEntityRegistry`
- `JdbcGraphIngestionLockProvider`

Create a `JdbcGraphStoreConfig` with an application-provided `DataSource`, run
`config.schema().initialize()` during application startup, and obtain stores from the config factory methods. Payloads
use a replaceable `JdbcGraphStoreSerializer` (Fastjson2 JSONB by default), while query and optimistic-lock fields are
projected into indexed columns. The built-in schema is a MySQL/H2-compatible development baseline. Production
applications should manage equivalent tables through their migration tool.

`JdbcGraphIngestionLockProvider` keeps one transaction and JDBC connection open per active lock so a process or
connection failure automatically releases the lock. Size the connection pool accordingly and configure the maximum
database lock wait with `lockWaitMillis`.
