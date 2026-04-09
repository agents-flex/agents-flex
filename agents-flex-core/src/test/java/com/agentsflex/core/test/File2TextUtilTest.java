package com.agentsflex.core.test;

import com.agentsflex.core.file2text.File2TextUtil;
import org.junit.Test;

import java.io.File;

public class File2TextUtilTest {

    @Test
    public void testHttpXlsx2Text() throws Exception {

        String url = "https://static.agentscenter.cn/attachment/1/2026/3/18/d92c59e5-2830-4971-8694-e4e42d93eba9/b.xlsx";
        String text = File2TextUtil.readFromHttpUrl(url);

        System.out.println(">>>>" + text);
    }

    @Test
    public void testReadFromDocxFile() {
        String filePath = "/Users/michael/Desktop/11122.docx";
        String text = File2TextUtil.readFromFile(new File(filePath));
        System.out.println( text);

        // 断言结果
    }
    @Test
    public void testReadFromDocFile() {
        String filePath = "/Users/michael/Desktop/3333.doc";
        String text = File2TextUtil.readFromFile(new File(filePath));
        System.out.println( text);

        // 断言结果
    }
    @Test
    public void testReadFromFile2() {
        String filePath = "/Users/michael/Desktop/222.pdf";
        String text = File2TextUtil.readFromFile(new File(filePath));
        System.out.println( text);

        // 断言结果
    }
}
