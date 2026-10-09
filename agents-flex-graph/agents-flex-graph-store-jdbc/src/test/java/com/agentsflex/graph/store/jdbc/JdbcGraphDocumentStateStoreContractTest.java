package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.ingestion.GraphDocumentStateStore;
import com.agentsflex.graph.testkit.AbstractGraphDocumentStateStoreContractTest;
import org.h2.jdbcx.JdbcDataSource;

import java.util.UUID;

/**
 * 在 H2 MySQL 兼容模式下验证 JDBC 文档状态 Store 的公共 SPI 契约。
 *
 * <p>每个测试使用独立内存数据库和表前缀，覆盖 revision CAS、并发首次写入、Space 隔离、
 * 历史幂等以及引用查询等与具体数据库实现无关的行为。</p>
 */
public class JdbcGraphDocumentStateStoreContractTest extends AbstractGraphDocumentStateStoreContractTest {
    /**
     * 创建已初始化且无历史数据的 JDBC 文档状态 Store。
     */
    @Override
    protected GraphDocumentStateStore createStore() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:graph_state_contract_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcGraphStoreConfig config = JdbcGraphStoreConfig.builder(dataSource)
            .tablePrefix("c" + UUID.randomUUID().toString().replace("-", "") + "_").build();
        config.schema().initialize();
        return config.documentStateStore();
    }
}
