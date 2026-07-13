package com.agentsflex.core.util;

import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * TypeConverter 边界爆破测试
 */
public class TypeConverterBoundaryFuzzTest {

    // ---- isDecimalString 语义漏洞: "." 被判合法 -> parseDouble 崩 ----
    @Test
    public void convert_dotToDouble_shouldThrow() {
        // "." 不是合法小数,但若 isDecimalString 误判为合法会走到 Double.parseDouble 崩
        try {
            TypeConverter.convert(".", Double.class);
            Assert.fail("应抛异常");
        } catch (Exception e) {
            // 预期抛异常
        }
    }

    @Test
    public void convert_signDotToDouble_shouldThrow() {
        try {
            TypeConverter.convert("+.", Double.class);
            Assert.fail("应抛异常");
        } catch (Exception e) {
        }
        try {
            TypeConverter.convert("-.", Double.class);
            Assert.fail("应抛异常");
        } catch (Exception e) {
        }
    }

    // ---- 正常小数应通过(对照) ----
    @Test
    public void convert_normalDecimal_ok() {
        Assert.assertEquals(1.5, TypeConverter.convert("1.5", Double.class), 0.0);
        Assert.assertEquals(-0.5, TypeConverter.convert("-0.5", Double.class), 0.0);
    }

    // ---- BigDecimal 精度(对照,确认无回归) ----
    @Test
    public void convert_doubleToBigDecimal_noPrecisionLoss() {
        BigDecimal bd = TypeConverter.convert(0.1d, BigDecimal.class);
        // Double 0.1 的 BigDecimal.valueOf 应为 0.1,而非 new BigDecimal(0.1) 的 0.1000000000000000055...
        Assert.assertEquals(new BigDecimal("0.1"), bd);
    }

    @Test
    public void convert_intToBigDecimal() {
        BigDecimal bd = TypeConverter.convert(123, BigDecimal.class);
        Assert.assertEquals(new BigDecimal("123"), bd);
    }

    // ---- 大 Long 转 BigInteger ----
    @Test
    public void convert_bigLongToBigInteger() {
        long big = 1234567890123L;
        BigInteger bi = TypeConverter.convert(big, BigInteger.class);
        Assert.assertEquals(BigInteger.valueOf(big), bi);
    }

    // ---- BigDecimal 转 BigInteger: num.longValue() 溢出? ----
    @Test
    public void convert_bigDecimalToBigInteger_overflow() {
        // BigDecimal 超过 Long 范围时, num.longValue() 会截断溢出
        BigDecimal big = new BigDecimal("99999999999999999999");  // 20位, 超 long
        BigInteger bi = TypeConverter.convert(big, BigInteger.class);
        Assert.assertEquals(new BigInteger("99999999999999999999"), bi);
    }

    // ---- Boolean 转换 ----
    @Test
    public void convert_booleanStrings() {
        Assert.assertEquals(true, TypeConverter.convert("yes", Boolean.class));
        Assert.assertEquals(false, TypeConverter.convert("off", Boolean.class));
        Assert.assertEquals(true, TypeConverter.convert(1, Boolean.class));
    }

    // ---- convert 带默认值, 失败应返回默认值而非抛异常 ----
    @Test
    public void convert_withDefault_onFailure() {
        Integer r = TypeConverter.convert("abc", Integer.class, -1);
        Assert.assertEquals(Integer.valueOf(-1), r);
    }

    // ---- null 入参 ----
    @Test
    public void convert_null_returnsNull() {
        Assert.assertNull(TypeConverter.convert(null, Integer.class));
    }

    // ---- Character 空字符串: 实现明确抛异常(对照,确认行为) ----
    @Test
    public void convert_emptyStringToChar_throws() {
        try {
            TypeConverter.convert("", Character.class);
            Assert.fail("应抛异常");
        } catch (Exception e) {
        }
        // 带默认值版应兜底返回默认值
        Character def = TypeConverter.convert("", Character.class, 'x');
        Assert.assertEquals(Character.valueOf('x'), def);
    }
}
