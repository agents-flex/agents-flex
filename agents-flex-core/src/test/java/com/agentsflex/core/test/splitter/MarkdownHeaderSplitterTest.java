package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.MarkdownHeaderSplitter;

import java.util.List;

public class MarkdownHeaderSplitterTest {

    public static void main(String[] args) {

        String markdown = "# Intro\n" +
            "Text\n" +
            "\n" +
            "## Real Section 1\n" +
            "\n" +
            "```java\n" +
            "// ## Not a header\n" +
            "public class Test {}\n" +
            "```\n" +
            "\n" +
            "## Real Section 2\n" +
            "\n" +
            "```md\n" +
            "## Fake header in code\n" +
            "```";

        MarkdownHeaderSplitter splitter = new MarkdownHeaderSplitter(2);
        List<Document> documents = splitter.split(Document.of(markdown));

        for (Document document : documents) {
            System.out.println("-------------------");
            System.out.println(document.getContent());
        }

    }
}
