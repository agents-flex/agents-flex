-- OpenSandbox 分布式会话 Store 参考表结构。
-- 不同数据库对 TEXT、BOOLEAN 的类型名称可能不同，生产环境可按所用数据库调整。
CREATE TABLE agents_flex_open_sandbox_conversations (
    storage_key VARCHAR(64) PRIMARY KEY,
    service_key VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    workspace_root VARCHAR(1024) NOT NULL,
    sandbox_id VARCHAR(255),
    workspace_ready BOOLEAN NOT NULL,
    prepared_skills TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);
