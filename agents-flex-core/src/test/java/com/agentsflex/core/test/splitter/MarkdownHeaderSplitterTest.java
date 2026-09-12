package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.MarkdownHeaderSplitter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class MarkdownHeaderSplitterTest {

    @Test
    public void shouldKeepShorterFencesInsideCodeBlock() {
        assertFencedSection("````markdown", "```\n# Example heading\n```", "````");
        assertFencedSection("~~~~markdown", "~~~\n# Example heading\n~~~", "~~~~");
    }

    @Test
    public void shouldNotCloseCodeBlockWithDifferentMarker() {
        assertFencedSection("```markdown", "~~~\n# Example heading\n~~~", "```");
        assertFencedSection("~~~markdown", "```\n# Example heading\n```", "~~~");
    }

    @Test
    public void shouldNotCloseCodeBlockWithTrailingText() {
        assertFencedSection("```", "```not-a-closing-fence\n# Example heading", "```");
        assertFencedSection("~~~", "~~~not-a-closing-fence\n# Example heading", "~~~");
    }

    @Test
    public void shouldResumeSplittingAfterMatchingOrLongerFence() {
        assertFencedSection("```markdown", "# Example heading", "```  \t");
        assertFencedSection("~~~markdown", "# Example heading", "~~~  \t");
        assertFencedSection("```markdown", "# Example heading", "````");
        assertFencedSection("~~~markdown", "# Example heading", "~~~~");
    }

    @Test
    public void shouldKeepUnclosedCodeBlockThroughEndOfDocument() {
        String markdown = "# Examples\n````markdown\n```\n# Example heading";

        List<Document> chunks = new MarkdownHeaderSplitter(1).split(Document.of(markdown));

        assertEquals(1, chunks.size());
        assertEquals(markdown, chunks.get(0).getContent());
        assertEquals("Examples", chunks.get(0).getMetadata("header_path"));
    }

    private void assertFencedSection(String opening, String body, String closing) {
        String firstSection = "# Examples\n" + opening + "\n" + body + "\n" + closing;
        String secondSection = "# Next\nText";
        String markdown = firstSection + "\n" + secondSection;

        List<Document> chunks = new MarkdownHeaderSplitter(1).split(Document.of(markdown));

        assertEquals(2, chunks.size());
        assertEquals(firstSection.trim(), chunks.get(0).getContent());
        assertEquals(secondSection, chunks.get(1).getContent());
        assertEquals("Examples", chunks.get(0).getMetadata("header_path"));
        assertEquals("Next", chunks.get(1).getMetadata("header_path"));
        assertEquals("0", chunks.get(0).getMetadata("start_line"));
        assertEquals(String.valueOf(firstSection.split("\n").length - 1), chunks.get(0).getMetadata("end_line"));
        assertEquals(String.valueOf(firstSection.split("\n").length), chunks.get(1).getMetadata("start_line"));
    }

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
