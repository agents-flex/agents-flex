package com.agentsflex.engines.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.search.engines.config.SearcherConfig;
import com.agentsflex.search.engines.lucene.LuceneSearcher;

import java.util.List;

public class TestLuceneCRUD {
    public static void main(String[] args) {
        // 1. 配置 Lucene 索引路径
        SearcherConfig config = new SearcherConfig();
        config.setIndexDirPath("./lucene_index"); // 设置索引目录路径

        // 2. 创建 LuceneSearcher 实例
        LuceneSearcher luceneSearcher = new LuceneSearcher(config);

        // 文档ID（用于更新和删除）
        String docId = "1";

        try {
            // ---- Step 1: 添加文档 ----
            System.out.println("【添加文档】");
            Document doc = new Document();
            doc.setId(docId);
            doc.setContent("这是初始的测试内容");
            doc.setTitle("初始标题");

            boolean addSuccess = luceneSearcher.addDocument(doc);
            System.out.println("添加文档结果：" + (addSuccess ? "成功" : "失败"));

            // 查询添加后的结果
            testSearch(luceneSearcher, "测试");

            // ---- Step 2: 更新文档 ----
            System.out.println("\n【更新文档】");
            Document updatedDoc = new Document();
            updatedDoc.setId(docId);
            updatedDoc.setContent("这是更新后的测试内容");
            updatedDoc.setTitle("更新后的标题");

            boolean updateSuccess = luceneSearcher.updateDocument(updatedDoc);
            System.out.println("更新文档结果：" + (updateSuccess ? "成功" : "失败"));

            // 查询更新后的结果
            testSearch(luceneSearcher, "更新");

            // ---- Step 3: 删除文档 ----
            System.out.println("\n【删除文档】");
            boolean deleteSuccess = luceneSearcher.deleteDocument(docId);
            System.out.println("删除文档结果：" + (deleteSuccess ? "成功" : "失败"));

            // 查询删除后的结果
            testSearch(luceneSearcher, "测试");

        } finally {
            // 关闭资源
            luceneSearcher.close();
        }
    }

    // 封装一个搜索方法，打印搜索结果
    private static void testSearch(LuceneSearcher searcher, String keyword) {
        List<com.agentsflex.core.document.Document> results = searcher.searchDocuments(keyword);
        if (results.isEmpty()) {
            System.out.println("没有找到匹配的文档。");
        } else {
            System.out.println("找到 " + results.size() + " 个匹配文档：");
            for (com.agentsflex.core.document.Document doc : results) {
                System.out.println("ID: " + doc.getId());
                System.out.println("标题: " + doc.getTitle());
                System.out.println("内容: " + doc.getContent());
                System.out.println("-----------------------------");
            }
        }
    }
}
