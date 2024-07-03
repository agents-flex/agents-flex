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
package com.agentsflex.document.parser.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.document.parser.PdfBoxDocumentParser;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class PdfBoxDocumentParserTest {

    @Test
    public void testParserPdf() throws FileNotFoundException {
        File file = new File(System.getProperty("user.dir"), "../../testresource/a.pdf");
        FileInputStream stream = new FileInputStream(file);
        PdfBoxDocumentParser parser = new PdfBoxDocumentParser();
        Document document = parser.parse(stream);
        System.out.println(document);
    }
}
