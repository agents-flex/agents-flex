package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.Chain;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ChainGetTest {

    @Test
    public void testGet() {

        AA aa = new AA();
        aa.bb = new BB();
        aa.bb.cc = "hello world";

        Chain chain = new Chain();
        chain.set("aa", aa);

        Assert.assertNotNull(chain.get("aa"));
        Assert.assertNotNull(chain.get("aa.bb"));
        Assert.assertEquals("hello world", chain.get("aa.bb.cc"));

        System.out.println(chain.get("aa.bb"));
        System.out.println(chain.get("aa.bb.cc"));
    }

    @Test
    public void testGet2() {

        AA aa1 = new AA();
        aa1.bb = new BB();
        aa1.bb.cc = "hello world1";

        AA aa2 = new AA();
        aa2.bb = new BB();
        aa2.bb.cc = "hello world2";

        Chain chain = new Chain();
        chain.set("aa", Arrays.asList(aa1, aa2));
        //memory.put("aa", Arrays.asList(aa1, aa2));

        System.out.println(chain.get("aa.bb.cc"));
        // List<String> : "hello world1","hello world2";
    }


    // 测试类
    public static class AA {
        public BB bb;
    }

    public static class BB {
        public String cc;
    }
}
