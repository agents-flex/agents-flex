package com.agentsflex.store.milvus;

import com.agentsflex.document.Document;
import com.agentsflex.store.StoreOptions;
import com.agentsflex.store.StoreResult;
import org.junit.Test;

public class MilvusDbTest {

    @Test
    public void test01() {
        MilvusVectorStoreConfig config = new MilvusVectorStoreConfig();
        config.setUri("http://127.0.0.1:19530");


        MilvusVectorStore store = new MilvusVectorStore(config);

        StoreResult result = store.store(Document.of("test"), StoreOptions.ofCollectionName("default"));
        System.out.println(result);
    }


}
