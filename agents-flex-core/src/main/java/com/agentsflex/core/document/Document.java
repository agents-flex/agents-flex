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

import com.agentsflex.core.store.VectorData;

public class Document extends VectorData {

    /**
     * Document ID
     */
    private Object id;

    /**
     * Document title
     */
    private String title;

    /**
     * Document Content
     */
    private String content;


    /**
     * 得分，目前只有在 rerank 场景使用
     */
    private Double score;


    public Document() {
    }

    public Document(String content) {
        this.content = content;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public Double getScore() {
        return score;
    }

    @Override
    public void setScore(Double score) {
        this.score = score;
    }

    public static Document of(String content){
        Document document = new Document();
        document.setContent(content);
        return document;
    }

    @Override
    public String toString() {
        return "Document{" +
            "id=" + id +
            ", title='" + title + '\'' +
            ", content='" + content + '\'' +
            ", score=" + score +
            '}';
    }
}
