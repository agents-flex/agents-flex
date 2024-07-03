package com.agentsflex.document.parser.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.document.parser.PoiDocumentParser;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class PoiDocumentParserTest {

    @Test
    public void testParserDocx() throws FileNotFoundException {
        File file = new File(System.getProperty("user.dir"), "../../testresource/a.doc");
        FileInputStream stream = new FileInputStream(file);
        PoiDocumentParser parser = new PoiDocumentParser();
        Document document = parser.parse(stream);
        System.out.println(document);
    }
}
