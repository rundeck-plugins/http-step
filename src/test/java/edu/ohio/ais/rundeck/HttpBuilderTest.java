package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.plugins.PluginLogger;
import org.apache.http.client.methods.RequestBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static edu.ohio.ais.rundeck.HttpBuilder.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class HttpBuilderTest {

    private HttpBuilder builder;
    private RequestBuilder request;

    @Before
    public void setUp() {
        builder = new HttpBuilder();
        request = mock(RequestBuilder.class);
    }

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

    // RUN-2569 / upstream issue #32: HttpBuilder.setHeaders previously threw
    // ClassCastException when YAML or JSON header values parsed as non-String
    // scalars (e.g. `Content-Length: 0`). These tests pin the fixed behavior.

    @Test
    public void setHeaders_yamlIntegerValue_setsStringifiedValue() {
        builder.setHeaders("Content-Length: 0", request);
        verify(request).setHeader("Content-Length", "0");
    }

    @Test
    public void setHeaders_jsonIntegerValue_setsStringifiedValue() {
        builder.setHeaders("{\"Content-Length\":0}", request);
        verify(request).setHeader("Content-Length", "0");
    }

    @Test
    public void setHeaders_yamlStringValue_setsValueUnchanged() {
        builder.setHeaders("Authorization: Bearer abc", request);
        verify(request).setHeader("Authorization", "Bearer abc");
    }

    @Test
    public void setHeaders_jsonStringValue_setsValueUnchanged() {
        builder.setHeaders("{\"X-Custom\":\"foo\"}", request);
        verify(request).setHeader("X-Custom", "foo");
    }

    @Test
    public void setHeaders_yamlMixedStringAndNumeric_setsBothCorrectly() {
        builder.setHeaders("Content-Length: 0\nX-Custom: foo", request);
        verify(request).setHeader("Content-Length", "0");
        verify(request).setHeader("X-Custom", "foo");
    }

    @Test
    public void setHeaders_yamlBooleanValue_setsStringifiedValue() {
        builder.setHeaders("X-Debug: true", request);
        verify(request).setHeader("X-Debug", "true");
    }

    @Test
    public void setHeaders_yamlListValue_setsStringifiedValueWithoutThrowing() {
        // Non-scalar values no longer throw ClassCastException; they are
        // coerced to their default toString() representation. This is a
        // deliberate behavior change relative to the pre-fix code, which
        // crashed on any non-String value.
        builder.setHeaders("Accept: [a, b]", request);
        verify(request).setHeader("Accept", "[a, b]");
    }

    @Test
    public void setHeaders_unparseableInput_logsAndDoesNotThrow() {
        PluginLogger log = mock(PluginLogger.class);
        builder.setLog(log);
        // A bareword is neither valid JSON nor a YAML map; both parsers
        // either throw or return a non-Map value, so setHeaders must log
        // the parse error and skip touching the request.
        builder.setHeaders("not a map", request);
        verify(log).log(0, "Error parsing the headers");
        verifyZeroInteractions(request);
    }

    @Test
    public void headerValueToString_wholeNumberDouble_emitsIntegerString() {
        // Gson parses all JSON numbers as Double by default; ensure that a
        // whole-number Double like 0.0 is emitted as "0" rather than "0.0"
        // so headers like Content-Length stay valid for strict HTTP servers.
        assertEquals("0", headerValueToString(0.0));
        assertEquals("1024", headerValueToString(1024.0));
        assertEquals("-1", headerValueToString(-1.0));
    }

    @Test
    public void headerValueToString_fractionalDouble_emitsDecimalString() {
        // Genuinely fractional Doubles must keep their decimal form rather
        // than being silently truncated to a long.
        assertEquals("1.5", headerValueToString(1.5));
    }

}
