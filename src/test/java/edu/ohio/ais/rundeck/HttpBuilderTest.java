package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.storage.StorageTree;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import org.apache.http.client.methods.RequestBuilder;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.rundeck.storage.api.Resource;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static edu.ohio.ais.rundeck.HttpBuilder.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class HttpBuilderTest {

    private HttpBuilder builder;
    private RequestBuilder request;
    private PluginStepContext pluginStepContext;
    private ExecutionContext executionContext;

    @Before
    public void setUp() {
        builder = new HttpBuilder();
        request = mock(RequestBuilder.class);

        // getAuthHeader always tries key storage first; with no storage tree
        // stubbed, SecretBundleUtil logs the failure and falls back to using
        // the raw option value as the password.
        pluginStepContext = mock(PluginStepContext.class);
        executionContext = mock(ExecutionContext.class);
        when(pluginStepContext.getExecutionContext()).thenReturn(executionContext);
        when(executionContext.getExecutionLogger()).thenReturn(mock(PluginLogger.class));
    }

    /**
     * Stub the key storage tree so the given path resolves to the given content.
     */
    private void stubStoragePassword(String path, final String content) {
        StorageTree storageTree = mock(StorageTree.class);
        @SuppressWarnings("unchecked")
        Resource<ResourceMeta> resource = mock(Resource.class);
        ResourceMeta meta = mock(ResourceMeta.class);

        when(executionContext.getStorageTree()).thenReturn(storageTree);
        when(storageTree.getResource(path)).thenReturn(resource);
        when(resource.getContents()).thenReturn(meta);
        try {
            when(meta.writeContent(any(OutputStream.class))).thenAnswer(new Answer<Long>() {
                @Override
                public Long answer(InvocationOnMock invocation) throws Throwable {
                    byte[] bytes = content.getBytes();
                    ((OutputStream) invocation.getArguments()[0]).write(bytes);
                    return (long) bytes.length;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    // The "Bearer" authentication type sends the resolved password verbatim as
    // a Bearer credential in the Authorization header.

    @Test
    public void getAuthHeader_bearerAuth_returnsBearerHeader() throws StepException {
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("password", "my-token");

        assertEquals("Bearer my-token", builder.getAuthHeader(pluginStepContext, options));
    }

    @Test
    public void getAuthHeader_bearerAuth_ignoresUsername() throws StepException {
        // Unlike BASIC, the username plays no part in the header.
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("username", "user");
        options.put("password", "my-token");

        assertEquals("Bearer my-token", builder.getAuthHeader(pluginStepContext, options));
    }

    @Test
    public void getAuthHeader_bearerAuth_doesNotContactTokenEndpoint() throws StepException {
        // No OAuth client should be built even if OAuth endpoints are left
        // configured from a previous authentication choice.
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("username", "client-id");
        options.put("password", "my-token");
        options.put("oauthTokenEndpoint", "http://localhost:1/token");

        assertEquals("Bearer my-token", builder.getAuthHeader(pluginStepContext, options));
        assertTrue("Expected no OAuth client to be created", builder.getOauthClients().isEmpty());
    }

    @Test
    public void getAuthHeader_bearerAuthWithStoragePath_usesStoredValue() throws StepException {
        stubStoragePassword("keys/my/token", "stored-token");

        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("password", "keys/my/token");

        assertEquals("Bearer stored-token", builder.getAuthHeader(pluginStepContext, options));
    }

    @Test
    public void getAuthHeader_bearerAuthWithoutPassword_throwsConfigurationFailure() {
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);

        try {
            builder.getAuthHeader(pluginStepContext, options);
            fail("Expected StepException for missing token");
        } catch (StepException se) {
            assertEquals(StepFailureReason.ConfigurationFailure, se.getFailureReason());
        }
    }

    @Test
    public void getAuthHeader_bearerAuthWithEmptyPassword_throwsConfigurationFailure() {
        // The option is present but blank, i.e. the field was left empty in the UI.
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("password", "");

        try {
            builder.getAuthHeader(pluginStepContext, options);
            fail("Expected StepException for empty token");
        } catch (StepException se) {
            assertEquals(StepFailureReason.ConfigurationFailure, se.getFailureReason());
        }
    }

    @Test
    public void getAuthHeader_bearerAuthWithBlankPassword_throwsConfigurationFailure() {
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BEARER);
        options.put("password", "   ");

        try {
            builder.getAuthHeader(pluginStepContext, options);
            fail("Expected StepException for blank token");
        } catch (StepException se) {
            assertEquals(StepFailureReason.ConfigurationFailure, se.getFailureReason());
        }
    }

    @Test
    public void getAuthHeader_basicAuth_returnsBasicHeader() throws StepException {
        // BASIC is unaffected by the new authentication type.
        Map<String, Object> options = new HashMap<>();
        options.put("authentication", AUTH_BASIC);
        options.put("username", "user");
        options.put("password", "my-token");

        assertEquals("Basic " + com.dtolabs.rundeck.core.utils.Base64.encode("user:my-token"),
                builder.getAuthHeader(pluginStepContext, options));
    }

    @Test
    public void getAuthHeader_noAuth_returnsNull() throws StepException {
        // A password on its own does not produce a header; the authentication
        // type has to select "Bearer" explicitly.
        Map<String, Object> options = new HashMap<>();
        options.put("password", "my-token");

        assertNull(builder.getAuthHeader(pluginStepContext, options));
    }

}
