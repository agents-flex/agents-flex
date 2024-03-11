/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.document.splitter;

import com.agentsflex.document.Splitter;
import com.agentsflex.document.Document;
import com.agentsflex.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleSplitter implements Splitter {

    private final String regex;

    public SimpleSplitter(String regex) {
        this.regex = regex;
    }

    @Override
    public List<Document> split(Document text) {
        if (text == null || StringUtil.noText(text.getContent())) {
            return Collections.emptyList();
        }
        String[] textArray = text.getContent().split(regex);
        List<Document> texts = new ArrayList<>(textArray.length);
        for (String textString : textArray) {
            Document newText = new Document();
            newText.setMetadatas(text.getMetadatas());
            newText.setContent(textString);
            texts.add(newText);
        }
        return texts;
    }
}
