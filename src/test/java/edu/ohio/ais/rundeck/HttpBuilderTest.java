package edu.ohio.ais.rundeck;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static edu.ohio.ais.rundeck.HttpBuilder.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HttpBuilderTest {

    @Test
    public void testGetStringOption() {
        Map<String, Object> options = new HashMap<>();

        String result = getStringOption(options, "missingKey");
        assertNull("Expected null when key is missing", result);

        options.put("nullKey", null);
        result = getStringOption(options, "nullKey");
        assertNull("Expected null when value is null", result);

        options.put("key", "value");
        result = getStringOption(options, "key");
        assertEquals("Expected the value associated with the key", "value", result);
    }

    @Test
    public void testGetStringOptionWithDefault() {
        Map<String, Object> options = new HashMap<>();

        String result = getStringOption(options, "missingKey", "defaultValue");
        assertEquals("Expected the default value when key is missing", "defaultValue", result);

        options.put("nullKey", null);
        result = getStringOption(options, "nullKey", "defaultValue");
        assertEquals("Expected the default value when value is null", "defaultValue", result);

        options.put("key", "value");
        result = getStringOption(options, "key", "defaultValue");
        assertEquals("Expected the value associated with the key", "value", result);
    }

    @Test
    public void testGetIntOption() {
        Map<String, Object> options = new HashMap<>();

        Integer result = getIntOption(options, "missingKey", 42);
        assertEquals("Expected the default value when key is missing", Integer.valueOf(42), result);

        options.put("nullKey", null);
        result = getIntOption(options, "nullKey", 42);
        assertEquals("Expected the default value when value is null", Integer.valueOf(42), result);

        options.put("key", 99);
        result = getIntOption(options, "key", 42);
        assertEquals("Expected the value associated with the key", Integer.valueOf(99), result);
    }

    @Test
    public void testGetBooleanOption() {
        Map<String, Object> options = new HashMap<>();

        Boolean result = getBooleanOption(options, "missingKey", true);
        assertEquals("Expected the default value when key is missing", Boolean.TRUE, result);

        options.put("nullKey", null);
        result = getBooleanOption(options, "nullKey", true);
        assertEquals("Expected the default value when value is null", Boolean.TRUE, result);

        options.put("key", false);
        result = getBooleanOption(options, "key", true);
        assertEquals("Expected the value associated with the key", Boolean.FALSE, result);
    }

}
