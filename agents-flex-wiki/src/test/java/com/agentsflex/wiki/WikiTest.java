/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.wiki;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WikiTest {

    @Test
    public void xmlEscapesMetadataAndUsesSafeFrontMatterItems() {
        Wiki wiki = new Wiki("a&b", "T <x>", "s > x");
        wiki.addFrontMatter("bad&key", "v < 1 & 2");

        String xml = wiki.toXml();

        assertTrue(xml.contains("<path>a&amp;b</path>"));
        assertTrue(xml.contains("<title>T &lt;x&gt;</title>"));
        assertTrue(xml.contains("<summary>s &gt; x</summary>"));
        assertTrue(xml.contains("<item key=\"bad&amp;key\">v &lt; 1 &amp; 2</item>"));
        assertFalse(xml.contains("<bad&key>"));
    }

    @Test
    public void markdownSerializesYamlSafelyAndDoesNotEmitNullContent() {
        Wiki wiki = new Wiki("a&b", "T <x>", "line: one\nline: two");
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        frontMatter.put("path", "overridden");
        frontMatter.put("note", "v: 1\nnext");
        frontMatter.put("tags", Arrays.asList("one", "two"));
        frontMatter.put("enabled", true);
        wiki.setFrontMatter(frontMatter);

        String markdown = wiki.toMarkdown();

        assertTrue(markdown.contains("path: \"a&b\""));
        assertTrue(markdown.contains("title: \"T <x>\""));
        assertTrue(markdown.contains("summary: \"line: one\\nline: two\""));
        assertTrue(markdown.contains("note: \"v: 1\\nnext\""));
        assertTrue(markdown.contains("tags: [\"one\", \"two\"]"));
        assertTrue(markdown.contains("enabled: true"));
        assertFalse(markdown.contains("path: \"overridden\""));
        assertFalse(markdown.endsWith("null"));
    }

    @Test
    public void addFrontMatterPreservesInsertionOrderWithLinkedHashMap() {
        Wiki wiki = new Wiki("path", "title");
        wiki.addFrontMatter("first", "1");
        wiki.addFrontMatter("second", "2");

        String markdown = wiki.toMarkdown();

        assertTrue(markdown.indexOf("first: \"1\"")
            < markdown.indexOf("second: \"2\""));
    }
}
