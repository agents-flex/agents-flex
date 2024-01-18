package com.agentsflex.core.test;

import com.agentsflex.prompt.template.SimplePromptTemplate;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PromptTemplateTest {

    @Test
    public void test001(){
        Map<String ,Object> map = new HashMap<>();
        map.put("x","abc");
        String string = SimplePromptTemplate.create("你好，{ } 今天是星期 :{ x }--xx--{x }---{  x }----{ x }").format(map).toString();
        System.out.println(string);
    }
}
