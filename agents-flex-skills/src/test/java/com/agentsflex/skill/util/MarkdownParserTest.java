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
package com.agentsflex.skill.util;

import com.agentsflex.skill.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * front matter 的起止分隔符是「一整行 ---」，不是「文本里出现的三个连字符」。
 * 逐行识别，值里的 --- 不会提前结束元数据，元数据也不会漏进正文。
 */
public class MarkdownParserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesFrontMatterAndBody() {
        MarkdownParser parser = new MarkdownParser(
            "---\nname: pdf\ndescription: Create PDF documents\n---\n\n# Heading\n\nBody\n");

        assertEquals("pdf", parser.getFrontMatter().get("name"));
        assertEquals("Create PDF documents", parser.getFrontMatter().get("description"));
        assertEquals("# Heading\n\nBody", parser.getContent());
    }

    /**
     * 描述里用 --- 当破折号时，旧实现把它当成结束分隔符：description 被截断，
     * 后面的元数据全部漏进正文。
     */
    @Test
    public void keepsDelimiterInsideValueFromEndingTheFrontMatter() {
        MarkdownParser parser = new MarkdownParser("---\n"
            + "name: pdf\n"
            + "description: Renders PDF---including forms\n"
            + "license: MIT\n"
            + "---\n"
            + "# PDF\n");

        Map<String, Object> frontMatter = parser.getFrontMatter();
        assertEquals("pdf", frontMatter.get("name"));
        assertEquals("Renders PDF---including forms", frontMatter.get("description"));
        assertEquals("MIT", frontMatter.get("license"));
        assertEquals("# PDF", parser.getContent());
    }

    @Test
    public void parsesCrlfDocuments() {
        MarkdownParser parser = new MarkdownParser(
            "---\r\nname: pdf\r\ndescription: Create PDF documents\r\n---\r\nBody\r\n");

        assertEquals("pdf", parser.getFrontMatter().get("name"));
        assertEquals("Create PDF documents", parser.getFrontMatter().get("description"));
        assertEquals("Body", parser.getContent());
    }

    /**
     * Windows 编辑器保存的 SKILL.md 常带 UTF-8 BOM；旧实现因此认不出开头的 ---，
     * 整份文件被当成正文，Skill 连 name 都没有。
     */
    @Test
    public void parsesDocumentsWithByteOrderMark() {
        MarkdownParser parser = new MarkdownParser(
            "\uFEFF---\nname: pdf\ndescription: Create PDF documents\n---\nBody");

        assertEquals("pdf", parser.getFrontMatter().get("name"));
        assertEquals("Body", parser.getContent());
    }

    @Test
    public void treatsDocumentWithoutClosingDelimiterAsBody() {
        String markdown = "---\nname: pdf\ndescription: Create PDF documents\n";
        MarkdownParser parser = new MarkdownParser(markdown);

        assertTrue(parser.getFrontMatter().isEmpty());
        assertEquals(markdown, parser.getContent());
    }

    @Test
    public void treatsPlainMarkdownAsBody() {
        String markdown = "# Title\n\n---\n\nname: pdf\n";
        MarkdownParser parser = new MarkdownParser(markdown);

        assertTrue(parser.getFrontMatter().isEmpty());
        assertEquals(markdown, parser.getContent());
    }

    /**
     * 起始分隔符同样必须独立成行：{@code ---name: pdf} 不是分隔符。
     */
    @Test
    public void requiresDelimiterToBeALineOfItsOwn() {
        String markdown = "---name: pdf\n---\nBody";
        MarkdownParser parser = new MarkdownParser(markdown);

        assertTrue(parser.getFrontMatter().isEmpty());
        assertEquals(markdown, parser.getContent());
    }

    /**
     * 端到端：从目录加载带 --- 描述和 BOM 的 SKILL.md，Skill 的名字和描述都要完整。
     */
    @Test
    public void loadsSkillFileWithDelimiterInDescriptionAndByteOrderMark() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("pdf");
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = ("---\n"
            + "name: pdf\n"
            + "description: Renders PDF---including forms\n"
            + "---\n"
            + "# PDF\n").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);
        Files.write(new File(skillDirectory, "SKILL.md").toPath(), content);

        List<Skill> skills = Skills.loadDirectory(temporaryFolder.getRoot().getAbsolutePath());

        assertEquals(1, skills.size());
        assertEquals("pdf", skills.get(0).name());
        assertEquals("Renders PDF---including forms", skills.get(0).getFrontMatter().get("description"));
        assertEquals("# PDF", skills.get(0).getContent());
        assertFalse(skills.get(0).toXml().contains("\uFEFF"));
    }
}
