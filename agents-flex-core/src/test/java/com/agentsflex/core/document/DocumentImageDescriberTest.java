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
package com.agentsflex.core.document;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class DocumentImageDescriberTest {

    @Test
    public void shouldAddDescriptionAfterImage() {
        RecordingChatModel model = new RecordingChatModel("一张展示季度增长趋势的折线图。");
        DocumentImageDescriber describer = new DocumentImageDescriber(model);
        Document document = Document.of("内容内容内容\n![](https://example.com/chart.png)");

        Document result = describer.describe(document);

        assertSame(document, result);
        assertEquals("内容内容内容\n![](https://example.com/chart.png)\n> 一张展示季度增长趋势的折线图。",
            document.getContent());
        assertEquals("https://example.com/chart.png", model.imageUrls.get(0));
    }

    @Test
    public void shouldDescribeMultipleImagesAndPreserveLineSeparator() {
        RecordingChatModel model = new RecordingChatModel("第一张图片", "第二张图片");
        DocumentImageDescriber describer = new DocumentImageDescriber(model);

        String result = describer.describe("![first](one.png \"title\")\r\ntext\r\n![second](<two.png>)\r\n");

        assertEquals("![first](one.png \"title\")\r\n> 第一张图片\r\ntext\r\n"
            + "![second](<two.png>)\r\n> 第二张图片\r\n", result);
        assertEquals("one.png", model.imageUrls.get(0));
        assertEquals("two.png", model.imageUrls.get(1));
    }

    @Test
    public void shouldSkipCodeFencesAndImagesWithExistingDescription() {
        RecordingChatModel model = new RecordingChatModel("新增描述");
        DocumentImageDescriber describer = new DocumentImageDescriber(model);
        String markdown = "![](done.png)\n> 已有描述\n\n```markdown\n![](sample.png)\n```\n![](new.png)";

        String result = describer.describe(markdown);

        assertEquals(markdown + "\n> 新增描述", result);
        assertEquals(1, model.imageUrls.size());
        assertEquals("new.png", model.imageUrls.get(0));
    }

    @Test
    public void shouldUseAltTextInCustomPromptAndNormalizeMultilineResponse() {
        RecordingChatModel model = new RecordingChatModel("> 第一行\n\n第二行");
        DocumentImageDescriber describer = new DocumentImageDescriber(model);
        describer.setPromptTemplate("describe: {alt}");

        String result = describer.describe("![销售图](chart.png)");

        assertEquals("![销售图](chart.png)\n> 第一行\n> 第二行", result);
        assertEquals("describe: 销售图", model.prompts.get(0));
    }

    @Test
    public void shouldKeepMarkdownUnchangedWhenModelReturnsNoMessage() {
        ChatModel model = new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                return new AiMessageResponse(null, null, null);
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };
        String markdown = "before\n![](image.png)\nafter";

        assertEquals(markdown, new DocumentImageDescriber(model).describe(markdown));
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String[] responses;
        private final List<String> imageUrls = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private int index;

        private RecordingChatModel(String... responses) {
            this.responses = responses;
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            SimplePrompt simplePrompt = (SimplePrompt) prompt;
            prompts.add(simplePrompt.getUserMessage().getContent());
            imageUrls.add(simplePrompt.getImageUrls().get(0));
            String response = responses[index++];
            return new AiMessageResponse(null, response, new AiMessage(response));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
