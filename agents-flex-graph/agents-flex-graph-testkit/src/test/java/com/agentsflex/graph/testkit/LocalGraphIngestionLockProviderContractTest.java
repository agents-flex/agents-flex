package com.agentsflex.graph.testkit;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionLockProvider;
import com.agentsflex.graph.extractor.ingestion.LocalGraphIngestionLockProvider;

/**
 * 使用 SDK 本地锁验证公共锁契约本身可以执行。
 */
public class LocalGraphIngestionLockProviderContractTest extends AbstractGraphIngestionLockProviderContractTest {
    @Override
    protected GraphIngestionLockProvider createProvider() {
        return new LocalGraphIngestionLockProvider();
    }
}
