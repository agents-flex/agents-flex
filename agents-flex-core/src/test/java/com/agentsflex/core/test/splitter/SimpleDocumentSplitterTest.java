package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.SimpleDocumentSplitter;
import org.junit.Test;

import java.util.List;

public class SimpleDocumentSplitterTest {
    String text = "MyBatis-Flex 是一个优雅的 MyBatis 增强框架，它非常轻量、同时拥有极高的性能与灵活性。我们可以轻松的使用 Mybaits-Flex 链接任何数据库，其内置的 QueryWrapper^亮点 帮助我们极大的减少了 SQL 编写的工作的同时，减少出错的可能性。\n" +
        "\n" +
        "总而言之，MyBatis-Flex 能够极大地提高我们的开发效率和开发体验，让我们有更多的时间专注于自己的事情。a";
    @Test
    public void test01(){
        SimpleDocumentSplitter splitter = new SimpleDocumentSplitter(20);
        List<Document> chunks = splitter.split(Document.of(text));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }

    @Test
    public void test02(){
        SimpleDocumentSplitter splitter = new SimpleDocumentSplitter(20,3);
        List<Document> chunks = splitter.split(Document.of(text));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }


}
