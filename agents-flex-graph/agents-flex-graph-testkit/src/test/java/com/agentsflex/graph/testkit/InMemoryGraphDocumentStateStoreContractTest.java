package com.agentsflex.graph.testkit;

import com.agentsflex.graph.extractor.ingestion.GraphDocumentStateStore;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphDocumentStateStore;

/**
 * 使用 SDK 内存状态存储验证公共文档状态契约本身可以执行。
 */
public class InMemoryGraphDocumentStateStoreContractTest extends AbstractGraphDocumentStateStoreContractTest {
    @Override
    protected GraphDocumentStateStore createStore() {
        return new InMemoryGraphDocumentStateStore();
    }
}
