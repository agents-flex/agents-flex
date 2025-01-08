package com.agentsflex.test;

import com.agentsflex.llm.spark.SparkLLM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author 王帅
 * @since 2024-04-10
 */
@SpringBootTest
class AgentsFlexTest {

    @Autowired
    private SparkLLM sparkLlm;

    @Test
    void testLlm() {
//        String result = sparkLlm.chat("“锄禾日当午”的下一句是什么？");
//        System.out.println(result);
//        Assertions.assertNotNull(result);
    }

}
