package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionLockProvider;
import com.agentsflex.graph.testkit.AbstractGraphIngestionLockProviderContractTest;
import org.h2.jdbcx.JdbcDataSource;

import java.util.UUID;

/**
 * 在 H2 MySQL 兼容模式下验证 JDBC 摄取锁的公共 SPI 契约。
 *
 * <p>契约确认同一 Space 和文档键严格互斥，同时不同 Space 或不同文档之间不会相互阻塞。</p>
 */
public class JdbcGraphIngestionLockProviderContractTest extends AbstractGraphIngestionLockProviderContractTest {
    /**
     * 创建使用独立锁表、等待上限为五秒的 JDBC 锁提供者。
     */
    @Override
    protected GraphIngestionLockProvider createProvider() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:graph_lock_contract_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcGraphStoreConfig config = JdbcGraphStoreConfig.builder(dataSource)
            .lockWaitMillis(5_000L)
            .tablePrefix("c" + UUID.randomUUID().toString().replace("-", "") + "_").build();
        config.schema().initialize();
        return config.lockProvider();
    }
}
