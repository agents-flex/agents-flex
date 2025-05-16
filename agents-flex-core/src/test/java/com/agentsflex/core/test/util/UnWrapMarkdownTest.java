package com.agentsflex.core.test.util;

import com.agentsflex.core.chain.node.LlmNode;
import org.junit.Assert;
import org.junit.Test;

public class UnWrapMarkdownTest {

    @Test
    public void testUnWrapMarkdown01() {
        String markdown = "```**Hello**, *world*!```";
        String expected = "**Hello**, *world*!";
        String actual = LlmNode.unWrapMarkdown(markdown);
        System.out.println(actual);
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testUnWrapMarkdown02() {
        String markdown = "```\n**Hello**, *world*!\n```";
        String expected = "**Hello**, *world*!";
        String actual = LlmNode.unWrapMarkdown(markdown);
        System.out.println(actual);
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testUnWrapMarkdown03() {
        String markdown = "```json\n**Hello**, *world*!\n```";
        String expected = "**Hello**, *world*!";
        String actual = LlmNode.unWrapMarkdown(markdown);
        System.out.println(actual);
        Assert.assertEquals(expected, actual);
    }

}
