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
        TextPromptTemplate promptTemplate = TextPromptTemplate.create("你好，{}  今天是星期 :{x}{ x }--xx--{x }---{  x }--}}--{ x} }");
        String string = promptTemplate
            .format(map).toString();
        System.out.println(string);

    }

    @Test
    public void test002() {
        Map<String, Object> map = new HashMap<>();
        map.put("useName", "Michael");
        map.put("aaa", "星期3");
        TextPromptTemplate promptTemplate = TextPromptTemplate.create("你好，{{  useName }}  今天是星期 :{{aaa   }} }----- {a}aa");
        String string = promptTemplate
            .format(map).toString();
        System.out.println(string);

    }
}
