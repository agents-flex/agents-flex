/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.MarkdownTableSplitter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarkdownTableSplitterTest {

    private static final String HEADER = "| Name | Value |\n| --- | --- |";

    @Test
    public void shouldSplitLongTableAndRepeatHeader() {
        Document source = Document.of("Before\n\n" + HEADER +
            "\n| alpha | 1111 |\n| beta | 2222 |\n| gamma | 3333 |\n\nAfter");
        source.putMetadata("source", "test.md");

        List<Document> chunks = new MarkdownTableSplitter(72).split(source, document -> document.getContent().hashCode());

        assertEquals(4, chunks.size());
        assertEquals("Before", chunks.get(0).getContent());
        assertEquals("After", chunks.get(3).getContent());
        for (int i = 1; i <= 2; i++) {
            assertTrue(chunks.get(i).getContent().startsWith(HEADER));
            assertEquals("markdown_table", chunks.get(i).getMetadata("content_type"));
            assertEquals("test.md", chunks.get(i).getMetadata("source"));
            assertTrue(chunks.get(i).getId() != null);
        }
        assertEquals("2", chunks.get(1).getMetadata("table_chunk_count"));
    }

    @Test
    public void shouldRepeatOverlappingRowsWhenTheyFit() {
        String markdown = HEADER +
            "\n| row-1 | aaaaa |\n| row-2 | bbbbb |\n| row-3 | ccccc |";

        List<Document> chunks = new MarkdownTableSplitter(72, 1).split(Document.of(markdown));

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("| row-2 | bbbbb |"));
        assertTrue(chunks.get(1).getContent().contains("| row-2 | bbbbb |"));
        assertTrue(chunks.get(1).getContent().contains("| row-3 | ccccc |"));
    }

    @Test
    public void shouldIgnoreTablesInsideFencedCodeBlocks() {
        String markdown = "````markdown\n```\n" + HEADER +
            "\n| alpha | 1111 |\n| beta | 2222 |\n````";

        List<Document> chunks = new MarkdownTableSplitter(20).split(Document.of(markdown));

        assertEquals(1, chunks.size());
        assertEquals(markdown, chunks.get(0).getContent());
        assertFalse(chunks.get(0).containsMetadata("content_type"));
    }

    @Test
    public void shouldKeepAnOversizedRowIntact() {
        String longValue = "a very long cell that must never be cut in the middle";
        String markdown = HEADER + "\n| key | " + longValue + " |";

        List<Document> chunks = new MarkdownTableSplitter(30).split(Document.of(markdown));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains(longValue));
        assertEquals(markdown, chunks.get(0).getContent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeOverlapRows() {
        new MarkdownTableSplitter(100, -1);
    }
}
