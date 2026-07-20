package com.agentsflex.core.file2text.extractor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MarkdownFormatterTest {

    @Test
    public void shouldFormatTablesWithConsistentColumnsAndEscaping() {
        StringBuilder output = new StringBuilder("Before table");

        MarkdownFormatter.appendTable(output, Arrays.asList(
            Arrays.asList("Name", "Notes"),
            Collections.singletonList("A|B\nC\\D")
        ));

        assertEquals("Before table\n\n"
            + "| Name | Notes |\n"
            + "| --- | --- |\n"
            + "| A\\|B C\\\\D |  |\n\n", output.toString());
    }

    @Test
    public void shouldNormalizeImageMetadataAndSkipEmptyImages() throws Exception {
        AtomicReference<String> mimeType = new AtomicReference<>();
        AtomicReference<String> fileName = new AtomicReference<>();
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);

        String imageUrl = MarkdownFormatter.handleImage((data, type, name) -> {
            mimeType.set(type);
            fileName.set(name);
            return "https://cdn.example.com/image.png";
        }, image, null, " ");

        assertEquals("application/octet-stream", mimeType.get());
        assertEquals("embedded-image", fileName.get());
        assertEquals("![Image](https://cdn.example.com/image.png)",
            MarkdownFormatter.formatImage(imageUrl));
        assertNull(MarkdownFormatter.handleImage((data, type, name) -> "unused",
            new byte[0], "image/png", "empty.png"));
    }
}
