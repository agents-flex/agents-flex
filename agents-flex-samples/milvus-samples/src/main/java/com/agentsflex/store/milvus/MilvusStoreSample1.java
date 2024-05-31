package com.agentsflex.store.milvus;

import com.agentsflex.document.Document;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.spark.SparkLlm;
import com.agentsflex.llm.spark.SparkLlmConfig;
import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.StoreResult;

import java.util.List;

public class MilvusStoreSample1 {

    public static void main(String[] args) {
        SparkLlmConfig sparkLlmConfig = new SparkLlmConfig();
        sparkLlmConfig.setAppId("****");
        sparkLlmConfig.setApiKey("****");
        sparkLlmConfig.setApiSecret("****");

        Llm llm = new SparkLlm(sparkLlmConfig);

        MilvusVectorStoreConfig config = new MilvusVectorStoreConfig();
        config.setUri("http://127.0.0.1:19530");
        config.setDefaultCollectionName("test_collection_04");
        MilvusVectorStore store = new MilvusVectorStore(config);
        store.setEmbeddingModel(llm);

        StoreResult result = store.store(Document.of("test"));
        System.out.println(result);

        SearchWrapper wrapper = new SearchWrapper();
        wrapper.text("test");
        List<Document> search = store.search(wrapper);
        System.out.println(search);
    }
}
