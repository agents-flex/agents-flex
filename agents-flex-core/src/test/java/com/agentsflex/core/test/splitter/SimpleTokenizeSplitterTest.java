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
package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.SimpleTokenizeSplitter;

import org.junit.Test;

import java.util.List;

public class SimpleTokenizeSplitterTest {
    String text = "MyBatis-Flex 是一个优雅的 MyBatis 增强框架，它非常轻量、同时拥有极高的性能与灵活性。我们可以轻松的使用 Mybaits-Flex 链接任何数据库，其内置的 QueryWrapper帮助我们极大的减少了 SQL 编写的工作的同时，减少出错的可能性。\n" +
        "总而言之，MyBatis-Flex 能够极大地提高我们的开发效率和开发体验，让我们有更多的时间专注于自己的事情。";

    String text2 = "AiEditor is a next-generation rich text editor for AI. It is developed based on Web Component and therefore supports almost any front-end framework such as Vue, React, Angular, Svelte, etc. It is adapted to PC Web and mobile terminals, and provides two themes: light and dark. In addition, it also provides flexible configuration, and developers can easily use it to develop any text editing application.";


    @Test
    public void test01() {
        SimpleTokenizeSplitter splitter = new SimpleTokenizeSplitter(20);
        List<Document> chunks = splitter.split(Document.of(text));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }

    @Test
    public void test02() {
        SimpleTokenizeSplitter splitter = new SimpleTokenizeSplitter(20, 4);
        List<Document> chunks = splitter.split(Document.of(text));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }

    @Test
    public void test03() {
        SimpleTokenizeSplitter splitter = new SimpleTokenizeSplitter(20);
        List<Document> chunks = splitter.split(Document.of(text2));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }

    @Test
    public void test04() {
        SimpleTokenizeSplitter splitter = new SimpleTokenizeSplitter(20, 3);
        List<Document> chunks = splitter.split(Document.of(text2));

        for (Document chunk : chunks) {
            System.out.println(">>>>>" + chunk.getContent());
        }
    }


}
