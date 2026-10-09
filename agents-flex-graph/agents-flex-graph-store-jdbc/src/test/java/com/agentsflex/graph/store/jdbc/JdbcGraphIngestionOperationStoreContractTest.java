package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionOperationStore;
import com.agentsflex.graph.testkit.AbstractGraphIngestionOperationStoreContractTest;
import org.h2.jdbcx.JdbcDataSource;

import java.util.UUID;

/**
 * 在 H2 MySQL 兼容模式下验证 JDBC 可恢复摄取操作 Store 的公共 SPI 契约。
 *
 * <p>契约覆盖操作与计划的原子创建、并发唯一性、阶段 CAS 以及稳定且有界的恢复扫描。</p>
 */
public class JdbcGraphIngestionOperationStoreContractTest extends AbstractGraphIngestionOperationStoreContractTest {
    /**
     * 创建已初始化且无历史数据的 JDBC 摄取操作 Store。
     */
    @Override
    protected GraphIngestionOperationStore createStore() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:graph_operation_contract_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcGraphStoreConfig config = JdbcGraphStoreConfig.builder(dataSource)
            .tablePrefix("c" + UUID.randomUUID().toString().replace("-", "") + "_").build();
        config.schema().initialize();
        return config.ingestionOperationStore();
    }
}
