/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.engines.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.search.engines.lucene.LuceneConfig;
import com.agentsflex.search.engines.lucene.LuceneSearcher;

import java.util.List;

public class TestLuceneCRUD {
    public static void main(String[] args) {
        // 1. 配置 Lucene 索引路径
        LuceneConfig config = new LuceneConfig();
        config.setIndexDirPath("./2lucene_index"); // 设置索引目录路径
        // 2. 创建 LuceneSearcher 实例
        LuceneSearcher luceneSearcher = new LuceneSearcher(config);

        // 文档ID（用于更新和删除）
        try {
            // ---- Step 1: 添加文档 ----
            System.out.println("【添加文档】");
            Document doc1 = new Document();
            doc1.setId(1);
            doc1.setTitle("利润最大化的原则");
            doc1.setContent("平台客服工具：是指拼多多平台开发并向企业提供的功能或工具，商家通过其专属账号登录平台客服工具后，可以与平台消费者取得\n" +
                "联系并为消费者提供客户服务");

            boolean addSuccess = luceneSearcher.addDocument(doc1);
            System.out.println("添加文档1结果：" + (addSuccess ? "成功" : "失败"));


            Document doc2 = new Document();
            doc2.setId(2);
            doc2.setTitle("企业获取报酬的活动");
            doc2.setContent("研究如何最合理地分配稀缺资源及不同的用途");

            boolean addSuccess1 = luceneSearcher.addDocument(doc2);
            System.out.println("添加文档2结果：" + (addSuccess1 ? "成功" : "失败"));

            // 查询添加后的结果
            testSearch(luceneSearcher, "企业");
            testSearch(luceneSearcher, "报酬");

            // ---- Step 2: 更新文档 ----
            System.out.println("\n【更新文档】");
            Document updatedDoc = new Document();
            updatedDoc.setId(1);
            updatedDoc.setContent("平台客服工具：是指拼多多平台开发并向商家提供的功能或工具，商家通过其专属账号登录平台客服工具后，可以与平台消费者取得\n" +
                "联系并为消费者提供客户服务2");

            boolean updateSuccess = luceneSearcher.updateDocument(updatedDoc);
            System.out.println("更新文档结果：" + (updateSuccess ? "成功" : "失败"));

            // 查询更新后的结果
            testSearch(luceneSearcher, "消费者");

            // ---- Step 3: 删除文档 ----
            System.out.println("\n【删除文档】");
            boolean deleteSuccess = luceneSearcher.deleteDocument(2);
            System.out.println("删除文档结果：" + (deleteSuccess ? "成功" : "失败"));

            // 查询删除后的结果
            testSearch(luceneSearcher, "报酬");

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
