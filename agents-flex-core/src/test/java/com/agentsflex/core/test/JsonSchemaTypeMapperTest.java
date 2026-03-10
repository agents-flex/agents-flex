package com.agentsflex.core.test;

import com.agentsflex.core.util.JsonSchemaTypeMapper;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class JsonSchemaTypeMapperTest {

    @Test
    public void testPrimitiveTypes() {
        assertEquals("integer", JsonSchemaTypeMapper.mapToSchemaType(int.class));
        assertEquals("integer", JsonSchemaTypeMapper.mapToSchemaType(Long.class));
        assertEquals("number", JsonSchemaTypeMapper.mapToSchemaType(Double.class));
        assertEquals("number", JsonSchemaTypeMapper.mapToSchemaType(BigDecimal.class));
        assertEquals("boolean", JsonSchemaTypeMapper.mapToSchemaType(Boolean.class));
        assertEquals("string", JsonSchemaTypeMapper.mapToSchemaType(String.class));
    }

    @Test
    public void testTimeTypes() {
        assertEquals("string", JsonSchemaTypeMapper.mapToSchemaType(LocalDateTime.class));
        assertEquals("string", JsonSchemaTypeMapper.mapToSchemaType(Instant.class));
        assertEquals("string", JsonSchemaTypeMapper.mapToSchemaType(LocalDate.class));
    }

    @Test
    public void testCollectionTypes() {
        assertEquals("array", JsonSchemaTypeMapper.mapToSchemaType(List.class));
        assertEquals("array", JsonSchemaTypeMapper.mapToSchemaType(Set.class));
        assertEquals("object", JsonSchemaTypeMapper.mapToSchemaType(Map.class));
        assertEquals("array", JsonSchemaTypeMapper.mapToSchemaType(int[].class));
    }

    @Test
    public void testGenericArrayType() throws NoSuchMethodException {
        Method method = TestClass.class.getMethod("stringList", List.class);
        Type genericType = method.getGenericParameterTypes()[0];
        assertEquals("string", JsonSchemaTypeMapper.resolveArrayItemType(genericType));
    }

    static class TestClass {
        public void stringList(List<String> list) {}
    }
}
