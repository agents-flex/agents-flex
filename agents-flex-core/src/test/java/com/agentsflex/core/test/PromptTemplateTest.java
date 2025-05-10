package com.agentsflex.core.test;

import com.agentsflex.core.prompt.template.TextPromptTemplate;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PromptTemplateTest {

    @Test
    public void test001() {
        Map<String, Object> map = new HashMap<>();
        map.put("x", "abc");
        TextPromptTemplate promptTemplate = TextPromptTemplate.of("你好，{}  今天是星期 :{{x}}{{ x }}--xx--{{x }---{{  x }}--}}--{ x} }");
        String string = promptTemplate
            .format(map).toString();
        System.out.println(string);

    }

    @Test
    public void test002() {
        Map<String, Object> map = new HashMap<>();
        map.put("useName", "Michael");
        map.put("aaa", "星期3");
        TextPromptTemplate promptTemplate = TextPromptTemplate.of("你好，{{  useName }}  今天是星期 :{{aaa   }}}----- {a}aa");
        String string = promptTemplate.formatToString(map);
        System.out.println(string);
    }


    @Test
    public void test003() {
        String templateStr = "你好 {{ user.name ?? '匿名' }}，欢迎来到 {{ site ?? 'AgentsFlex.com' }}！";
        TextPromptTemplate template = new TextPromptTemplate(templateStr);

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
}
