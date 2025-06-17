package com.agentsflex.search.engines.es;

import com.agentsflex.core.document.Document;
import com.agentsflex.engines.es.ElasticSearcher;
import com.agentsflex.search.engines.config.SearcherConfig;

import java.math.BigInteger;
import java.util.List;

public class ElasticSearcherTest {
    public static void main(String[] args) throws Exception {
        // 创建工具类实例 (忽略SSL证书，如果有认证则提供用户名密码)
        SearcherConfig searcherConfig = new SearcherConfig();
        searcherConfig.setHost("https://127.0.0.1:9200");
        searcherConfig.setUserName("elastic");
        searcherConfig.setPassword("zZWr1Oiek1SUeSpOnvMc");
        searcherConfig.setIndexName("aiknowledge");
        ElasticSearcher esUtil = new ElasticSearcher(searcherConfig);
        Document document = new Document();
        document.setContent("商家");
        document.setId(BigInteger.valueOf(1));
//        esUtil.updateDocument(document);
//        esUtil.addDocument(document);
        List<Document> res = esUtil.searchDocuments("交流时间");
//        esUtil.deleteDocument(1);
        esUtil.close();
    }
}
