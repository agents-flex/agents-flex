package com.agentsflex.engines.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.search.engines.config.SearcherConfig;
import com.agentsflex.search.engines.lucene.LuceneSearcher;

import java.util.List;

public class TestLuceneSearcher {
    public static void main(String[] args) {
        // 1. 配置 Lucene 索引路径
        SearcherConfig config = new SearcherConfig();
        config.setIndexName("./lucene_index");

        // 2. 创建 searcher 实例
        LuceneSearcher luceneSearcher = new LuceneSearcher(config);

        // 3. 添加测试文档
        Document doc1 = new Document();
        doc1.setId("1");
        doc1.setContent("这是一个关于 Lucene 的测试内容");
        doc1.setTitle("Lucene 测试文档 1");
        luceneSearcher.addDocument(doc1);

        // 4. 执行搜索
        List<Document> results = luceneSearcher.searchDocuments("Lucene");

        // 5. 输出结果
        System.out.println("找到 " + results.size() + " 个匹配文档：");
        for (com.agentsflex.core.document.Document doc : results) {
            System.out.println("ID: " + doc.getId());
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent());
            System.out.println("-----------------------------");
        }

        // 6. 关闭资源
        luceneSearcher.close();
    }
}
