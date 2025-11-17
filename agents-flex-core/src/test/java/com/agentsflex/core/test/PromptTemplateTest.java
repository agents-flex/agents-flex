package com.agentsflex.core.test;

import com.agentsflex.core.prompt.PromptTemplate;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PromptTemplateTest {

    @Test
    public void test002() {
        Map<String, Object> map = new HashMap<>();
        map.put("useName", "Michael");
        map.put("aaa", "星期3");
        PromptTemplate promptTemplate = PromptTemplate.of("你好，{{  useName }}  今天是星期 :{{aaa   }}}----- {a}aa");
        String string = promptTemplate.format(map);
        System.out.println(string);
    }


    @Test
    public void test003() {
        String templateStr = "你好 {{ user.name ?? '匿名' }}，欢迎来到 {{ site ?? 'AgentsFlex.com' }}！";
        PromptTemplate template = new PromptTemplate(templateStr);

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Michael");
        params.put("user", user);
        params.put("site", "AIFlowy.tech");

        System.out.println(template.format(params));
        // 输出：你好 Michael，欢迎来到 AIFlowy.tech！

        System.out.println(template.format(new HashMap<>()));
        // 输出：你好 匿名，欢迎来到 AgentsFlex.com！
    }

    @Test
    public void test004() {
        String jsonTemplate = "{\n" +
            "\"prompt\":\"{{prompt}}\",\n" +
            "\"image_url\":\"{{image }}\"\n" +
            "}";

        String prompt = jsonTemplate;
        String image = "http://image.jpg";
        PromptTemplate template = new PromptTemplate(jsonTemplate);


        Map<String, Object> params = new HashMap<>();
        params.put("prompt", prompt);
        params.put("image", image);

        System.out.println(template.format(params, true));
    }


    @Test
    public void test005() {
        String jsonTemplate = "{\n" +
            "\"prompt\":\"{{prompt}}\",\n" +
            "\"image_url\":\"{{image ?? ccc ?? 'haha'}}\"\n" +
            "}";

        String prompt = "你好";
        PromptTemplate template = new PromptTemplate(jsonTemplate);


        Map<String, Object> params = new HashMap<>();
        params.put("prompt", prompt);
//        params.put("image", image);

        System.out.println(template.format(params, true));
    }
}
