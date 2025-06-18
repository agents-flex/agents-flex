package com.agentsflex.search.engines.es;

import com.agentsflex.core.document.Document;
import com.agentsflex.engine.es.ESConfig;
import com.agentsflex.engine.es.ElasticSearcher;

import java.math.BigInteger;
import java.util.List;

public class ElasticSearcherTest {
    public static void main(String[] args) throws Exception {
        // 创建工具类实例 (忽略SSL证书，如果有认证则提供用户名密码)
        ESConfig searcherConfig = new ESConfig();
        searcherConfig.setHost("https://127.0.0.1:9200");
        searcherConfig.setUserName("elastic");
        searcherConfig.setPassword("Cn_=EEwD1s8jgVaaDHWE");
        searcherConfig.setIndexName("aiknowledge");
        ElasticSearcher esUtil = new ElasticSearcher(searcherConfig);
        Document document = new Document();
        document.setContent("平台客服工具：是指拼多多平台开发并向商家提供的功能或工具，商家通过其专属账号登录平台客服工具后，可以与平台消费者取得\\n\" +\n" +
            "                \"联系并为消费者提供客户服务");
        document.setId(BigInteger.valueOf(1));
//        esUtil.updateDocument(document);
//        esUtil.addDocument(document);
        List<Document> res = esUtil.searchDocuments("消费者");
//        esUtil.deleteDocument(1);

        esUtil.close();
    }
}
