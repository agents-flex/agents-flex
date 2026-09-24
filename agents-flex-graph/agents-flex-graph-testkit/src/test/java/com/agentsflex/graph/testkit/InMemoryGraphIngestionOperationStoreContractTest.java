package com.agentsflex.graph.testkit;

import com.agentsflex.graph.extractor.incremental.GraphIngestionOperationStore;
import com.agentsflex.graph.extractor.incremental.InMemoryGraphIngestionOperationStore;

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
