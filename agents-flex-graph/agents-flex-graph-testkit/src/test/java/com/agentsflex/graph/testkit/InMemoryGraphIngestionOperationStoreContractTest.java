package com.agentsflex.graph.testkit;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionOperationStore;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphIngestionOperationStore;

/**
 * 使用 SDK 内存操作日志验证公共恢复存储契约本身可以执行。
 */
public class InMemoryGraphIngestionOperationStoreContractTest
    extends AbstractGraphIngestionOperationStoreContractTest {
    @Override
    protected GraphIngestionOperationStore createStore() {
        return new InMemoryGraphIngestionOperationStore();
    }
}
