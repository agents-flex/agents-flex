package com.agentsflex.core.test;

import com.agentsflex.core.util.StringUtil;
import org.junit.Assert;
import org.junit.Test;

public class StringUtilTest {

    @Test
    public void testHasText_WithValidText() {
        Assert.assertTrue(StringUtil.hasText("hello"));
        Assert.assertTrue(StringUtil.hasText(" hello "));
        Assert.assertTrue(StringUtil.hasText("a"));
    }

    @Test
    public void testHasText_WithNull() {
        Assert.assertFalse(StringUtil.hasText(null));
    }

    @Test
    public void testHasText_WithEmpty() {
        Assert.assertFalse(StringUtil.hasText(""));
    }

    @Test
    public void testHasText_WithWhitespace() {
        Assert.assertFalse(StringUtil.hasText("   "));
        Assert.assertFalse(StringUtil.hasText("\t"));
        Assert.assertFalse(StringUtil.hasText("\n"));
        Assert.assertFalse(StringUtil.hasText("\r"));
        Assert.assertFalse(StringUtil.hasText(" \t\n\r "));
    }

    @Test
    public void testAllHasText_AllValid() {
        Assert.assertTrue(StringUtil.allHasText("hello", "world", "test"));
        Assert.assertTrue(StringUtil.allHasText("a", "b"));
    }

    @Test
    public void testAllHasText_WithInvalid() {
        Assert.assertFalse(StringUtil.allHasText("hello", null, "world"));
        Assert.assertFalse(StringUtil.allHasText("hello", "", "world"));
        Assert.assertFalse(StringUtil.allHasText("hello", "   ", "world"));
    }

    @Test
    public void testAllHasText_EmptyArgs() {
        Assert.assertFalse(StringUtil.allHasText());
    }

    @Test
    public void testAnyHasText_AtLeastOneValid() {
        Assert.assertTrue(StringUtil.anyHasText(null, "", "hello", null));
        Assert.assertTrue(StringUtil.anyHasText("hello", null, ""));
    }

    @Test
    public void testAnyHasText_AllInvalid() {
        Assert.assertFalse(StringUtil.anyHasText(null, "", "   "));
        Assert.assertFalse(StringUtil.anyHasText(null, null));
        Assert.assertFalse(StringUtil.anyHasText("", "", "   "));
    }

    @Test
    public void testNoText_WithNullOrWhitespace() {
        Assert.assertTrue(StringUtil.noText(null));
        Assert.assertTrue(StringUtil.noText(""));
        Assert.assertTrue(StringUtil.noText("   "));
        Assert.assertTrue(StringUtil.noText("\t\n\r"));
    }

    @Test
    public void testNoText_WithValidText() {
        Assert.assertFalse(StringUtil.noText("hello"));
        Assert.assertFalse(StringUtil.noText(" a "));
    }

    @Test
    public void testAllNoText_AllInvalid() {
        Assert.assertTrue(StringUtil.allNoText(null, "", "   "));
        Assert.assertTrue(StringUtil.allNoText(null, null));
        Assert.assertTrue(StringUtil.allNoText("", "   ", "\n"));
    }

    @Test
    public void testAllNoText_AtLeastOneValid() {
        Assert.assertFalse(StringUtil.allNoText(null, "hello", ""));
        Assert.assertFalse(StringUtil.allNoText("", "a", "   "));
    }

    @Test
    public void testAnyNoText_AtLeastOneInvalid() {
        Assert.assertTrue(StringUtil.anyNoText("hello", null, "world"));
        Assert.assertTrue(StringUtil.anyNoText("hello", "", "world"));
        Assert.assertTrue(StringUtil.anyNoText("   ", "hello", "world"));
    }

    @Test
    public void testAnyNoText_AllValid() {
        Assert.assertFalse(StringUtil.anyNoText("hello", "world", "test"));
        Assert.assertFalse(StringUtil.anyNoText("a", "b"));
    }

    @Test
    public void testFirstHasText_FindFirstValid() {
        Assert.assertEquals("hello", StringUtil.firstHasText(null, "", "   ", "hello", "world"));
        Assert.assertEquals("world", StringUtil.firstHasText(null, "world", "hello"));
        Assert.assertEquals("a", StringUtil.firstHasText("a", null, "b"));
    }

    @Test
    public void testFirstHasText_NoValid() {
        Assert.assertNull(StringUtil.firstHasText(null, "", "   "));
        Assert.assertNull(StringUtil.firstHasText(null, null));
        Assert.assertNull(StringUtil.firstHasText());
    }

    @Test
    public void testFirstHasText_NullArray() {
        Assert.assertNull(StringUtil.firstHasText(null));
    }

    @Test
    public void testIsJsonObject_ValidJson() {
        Assert.assertTrue(StringUtil.isJsonObject("{}"));
        Assert.assertTrue(StringUtil.isJsonObject("{\"key\":\"value\"}"));
        Assert.assertTrue(StringUtil.isJsonObject("  {\"key\":\"value\"}  "));
        Assert.assertTrue(StringUtil.isJsonObject("{ \"name\": \"test\", \"age\": 18 }"));
    }

    @Test
    public void testIsJsonObject_InvalidJson() {
        Assert.assertFalse(StringUtil.isJsonObject(null));
        Assert.assertFalse(StringUtil.isJsonObject(""));
        Assert.assertFalse(StringUtil.isJsonObject("   "));
        Assert.assertFalse(StringUtil.isJsonObject("{invalid}"));
        Assert.assertFalse(StringUtil.isJsonObject("[1,2,3]"));
        Assert.assertFalse(StringUtil.isJsonObject("\"string\""));
        Assert.assertFalse(StringUtil.isJsonObject("123"));
        Assert.assertFalse(StringUtil.isJsonObject("{open"));
        Assert.assertFalse(StringUtil.isJsonObject("close}"));
    }

    @Test
    public void testNotJsonObject() {
        Assert.assertTrue(StringUtil.notJsonObject(null));
        Assert.assertTrue(StringUtil.notJsonObject(""));
        Assert.assertTrue(StringUtil.notJsonObject("[1,2,3]"));
        Assert.assertFalse(StringUtil.notJsonObject("{}"));
        Assert.assertFalse(StringUtil.notJsonObject("{\"key\":\"value\"}"));
    }

    @Test
    public void testIsNumeric_ValidNumbers() {
        Assert.assertTrue(StringUtil.isNumeric("0"));
        Assert.assertTrue(StringUtil.isNumeric("123"));
        Assert.assertTrue(StringUtil.isNumeric("+123"));
        Assert.assertTrue(StringUtil.isNumeric("-123"));
        Assert.assertTrue(StringUtil.isNumeric("9999999999"));
    }

    @Test
    public void testIsNumeric_InvalidNumbers() {
        Assert.assertFalse(StringUtil.isNumeric(null));
        Assert.assertFalse(StringUtil.isNumeric(""));
        Assert.assertFalse(StringUtil.isNumeric("+"));
        Assert.assertFalse(StringUtil.isNumeric("-"));
        Assert.assertFalse(StringUtil.isNumeric("12.34"));
        Assert.assertFalse(StringUtil.isNumeric("abc"));
        Assert.assertFalse(StringUtil.isNumeric("123abc"));
        Assert.assertFalse(StringUtil.isNumeric("a123"));
        Assert.assertFalse(StringUtil.isNumeric("1 2 3"));
    }

    @Test
    public void testIsNumeric_EdgeCases() {
        Assert.assertFalse(StringUtil.isNumeric("0x123"));
        Assert.assertFalse(StringUtil.isNumeric("12e3"));
        Assert.assertFalse(StringUtil.isNumeric("12.0"));
    }
}
