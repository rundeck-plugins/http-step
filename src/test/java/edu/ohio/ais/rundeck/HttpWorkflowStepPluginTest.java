package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.FrameworkProject;
import com.dtolabs.rundeck.core.common.FrameworkProjectMgr;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.workflow.steps.PluginStepContextImpl;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.utils.IPropertyLookup;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import edu.ohio.ais.rundeck.util.OAuthClientTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

public class HttpWorkflowStepPluginTest {
    protected static final String REMOTE_URL = "/trigger";
    protected static final String BOGUS_URL = "/bogus";
    protected static final String REMOTE_BASIC_URL = "/trigger-basic";
    protected static final String REMOTE_SLOW_URL = "/slow-trigger";
    protected static final String REMOTE_OAUTH_URL = "/oauth";
    protected static final String REMOTE_OAUTH_EXPIRED_URL = "/oauth-expired";
    protected static final String ERROR_URL_500 = "/error500";
    protected static final String ERROR_URL_401 = "/error401";
    protected static final String NO_CONTENT_URL = "/nocontent204";
    protected static final String OAUTH_CLIENT_MAP_KEY = OAuthClientTest.CLIENT_VALID + "@"
            + OAuthClientTest.BASE_URI + OAuthClientTest.ENDPOINT_TOKEN;

    protected static final int REQUEST_TIMEOUT = 2*1000;
    protected static final int SLOW_TIMEOUT = 3*1000;

    protected HttpWorkflowStepPlugin plugin;
    protected OAuthClientTest oAuthClientTest = new OAuthClientTest();

    protected Map<String, Object> configuration;
    protected Map<String, Map<String, String>> dataContext;
    protected PluginStepContext pluginContext;
    protected PluginLogger pluginLogger;

    /**
     * Setup options for simple execution for the given method.
     * @param method HTTP Method to use.
     * @return Options for the execution.
     */
    public Map<String, Object> getExecutionOptions(String method) {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_URL);
        options.put("method", method);

        return options;
    }

    /**
     * Setup options for execution for the given method using HTTP BASIC.
     * @param method HTTP Method to use.
     * @return Options for the execution.
     */
    public Map<String, Object> getBasicOptions(String method) {
        Map<String, Object> options = getExecutionOptions(method);

        options.put("username", OAuthClientTest.CLIENT_VALID);
        options.put("password", OAuthClientTest.CLIENT_SECRET);
        options.put("authentication", HttpBuilder.AUTH_BASIC);

        return options;
    }

    /**
     * Setup options for simple execution for the given method using OAuth 2.0.
     * @param method HTTP Method to use.
     * @return Options for the execution.
     */
    public Map<String, Object> getOAuthOptions(String method) {
        Map<String, Object> options = getBasicOptions(method);

        options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_OAUTH_URL);
        options.put("oauthTokenEndpoint", OAuthClientTest.BASE_URI + OAuthClientTest.ENDPOINT_TOKEN);
        options.put("oauthValidateEndpoint", OAuthClientTest.BASE_URI + OAuthClientTest.ENDPOINT_VALIDATE);
        options.put("authentication", HttpBuilder.AUTH_OAUTH2);

        return options;
    }

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(18089);

    @Before
    public void setUp() {
        plugin = new HttpWorkflowStepPlugin();
        oAuthClientTest.setUp(); // We need to setup the OAuth endpoints too.

        // Test all endpoints by simply iterating.
        for(String method : HttpBuilder.HTTP_METHODS) {
            // Simple endpoint
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(REMOTE_URL)).atPriority(100)
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)));

            // HTTP Basic
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(REMOTE_BASIC_URL))
                    .withBasicAuth(OAuthClientTest.CLIENT_VALID, OAuthClientTest.CLIENT_SECRET)
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)));

            // OAuth with a fresh token
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(REMOTE_OAUTH_URL))
                    .withHeader("Authorization", WireMock.equalTo("Bearer " + OAuthClientTest.ACCESS_TOKEN_VALID))
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)));

            // BASIC that returns a 401
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(ERROR_URL_401))
                    .willReturn(WireMock.aResponse()
                            .withStatus(401)));

            // OAuth with an expired token
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(REMOTE_OAUTH_EXPIRED_URL))
                    .withHeader("Authorization", WireMock.equalTo("Bearer " + OAuthClientTest.ACCESS_TOKEN_EXPIRED))
                    .willReturn(WireMock.aResponse()
                            .withStatus(401)));
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(REMOTE_OAUTH_EXPIRED_URL))
                    .withHeader("Authorization", WireMock.equalTo("Bearer " + OAuthClientTest.ACCESS_TOKEN_VALID))
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)));

            // 500 Error
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(ERROR_URL_500))
                    .willReturn(WireMock.aResponse()
                            .withStatus(500)));

            // 204 No Content
            WireMock.stubFor(WireMock.request(method, WireMock.urlEqualTo(NO_CONTENT_URL))
                    .willReturn(WireMock.aResponse()
                            .withStatus(204)));
        }

        // Simple bogus URL that yields a 404
        WireMock.stubFor(WireMock.request("GET", WireMock.urlEqualTo(BOGUS_URL))
                        .willReturn(WireMock.aResponse().withStatus(404)));

        // Timeout test
        WireMock.stubFor(WireMock.request("GET", WireMock.urlEqualTo(REMOTE_SLOW_URL))
                .willReturn(WireMock.aResponse().withFixedDelay(SLOW_TIMEOUT).withStatus(200)));


        pluginContext = Mockito.mock(PluginStepContext.class);
        pluginLogger = Mockito.mock(PluginLogger.class);
        ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);
        when(executionContext.getExecutionLogger()).thenReturn(pluginLogger);
        when(pluginContext.getLogger()).thenReturn(pluginLogger);
        when(pluginContext.getExecutionContext()).thenReturn(executionContext);

        dataContext =new HashMap<>();
        Mockito.when(pluginContext.getDataContext()).thenReturn(dataContext);

        // Mock the necessary objects
        Framework framework = Mockito.mock(Framework.class);
        FrameworkProjectMgr frameworkProjectMgr = Mockito.mock(FrameworkProjectMgr.class);
        FrameworkProject frameworkProject = Mockito.mock(FrameworkProject.class);
        IPropertyLookup frameworkProperties = Mockito.mock(IPropertyLookup.class);

        when(pluginContext.getFramework()).thenReturn(framework);
        when(framework.getFrameworkProjectMgr()).thenReturn(frameworkProjectMgr);
        when(frameworkProjectMgr.getFrameworkProject(anyString())).thenReturn(frameworkProject);
        when(frameworkProject.getProperties()).thenReturn(new HashMap<String, String>());
        when(framework.getPropertyLookup()).thenReturn(frameworkProperties);
        when(frameworkProperties.hasProperty(anyString())).thenReturn(true);

    }

    @Test()
    public void canGetPluginDescription() {
        Description description = this.plugin.getDescription();

        assertEquals(description.getName(), HttpWorkflowStepPlugin.SERVICE_PROVIDER_NAME);
    }

    @Test()
    public void canValidateConfiguration() {
        Map<String, Object> options = new HashMap<>();

        try {
            this.plugin.executeStep(pluginContext, options);
            fail("Expected configuration exception.");
        } catch (StepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }

        options.put("remoteUrl", REMOTE_URL);
        options.put("method", "GET");
        options.put("authentication", HttpBuilder.AUTH_BASIC);
        options.put("printResponseCode", "true");

        try {
            this.plugin.executeStep(pluginContext, options);
            fail("Expected configuration exception.");
        } catch (StepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }

        options.put("authentication", HttpBuilder.AUTH_OAUTH2);

        try {
            this.plugin.executeStep(pluginContext, options);
            fail("Expected configuration exception.");
        } catch (StepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }
    }

    @Test()
    public void canCallSimpleEndpoint() throws StepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            this.plugin.executeStep(pluginContext, this.getExecutionOptions(method));
        }
    }

    @Test()
    public void canSetCustomTimeout() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_URL);
        options.put("method", "GET");
        options.put("timeout", REQUEST_TIMEOUT);

        this.plugin.executeStep(pluginContext, options);

        try {
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_SLOW_URL);
            this.plugin.executeStep(pluginContext, options);
            fail("Expected exception " + StepException.class.getCanonicalName() + " not thrown.");
        } catch(StepException se) {}

        options.put("timeout", SLOW_TIMEOUT + 1000);
        this.plugin.executeStep(pluginContext, options);
    }

    @Test()
    public void canCallBasicEndpoint() throws StepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            Map<String, Object> options = this.getBasicOptions(method);
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_BASIC_URL);
            options.put("printResponseCode", "true");

            this.plugin.executeStep(pluginContext, options);
        }
    }

    @Test(expected = StepException.class)
    public void canHandle500Error() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_500);
        options.put("method", "GET");
        options.put("printResponseCode", "true");

        this.plugin.executeStep(pluginContext, options);
    }

    @Test(expected = StepException.class)
    public void canHandleBadUrl() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + BOGUS_URL);
        options.put("method", "GET");

        this.plugin.executeStep(pluginContext, options);
    }

    @Test(expected = StepException.class)
    public void canHandleBadHost() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", "http://neverGoingToBe.aProperUrl/bogus");
        options.put("method", "GET");

        this.plugin.executeStep(pluginContext, options);
    }

    @Test(expected = StepException.class)
    public void canHandleBASICWrongAuthType() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_401);
        options.put("method", "GET");
        options.put("username", OAuthClientTest.CLIENT_VALID);
        options.put("password", OAuthClientTest.CLIENT_SECRET);
        options.put("authentication", HttpBuilder.AUTH_BASIC);

        this.plugin.executeStep(pluginContext, options);
    }

    @Test(expected = StepException.class)
    public void canHandleAuthenticationRequired() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_401);
        options.put("method", "GET");
        options.put("authentication", HttpBuilder.AUTH_BASIC);

        this.plugin.executeStep(pluginContext, options);
    }

    @Test()
    public void canCallOAuthEndpoint() throws StepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            this.plugin.executeStep(pluginContext, this.getOAuthOptions(method));
        }
    }

    @Test()
    public void canCallOAuthEndpointWithExpiredToken() throws StepException {
        this.plugin.oauthClients.put(OAUTH_CLIENT_MAP_KEY, this.oAuthClientTest.setupClient(OAuthClientTest.ACCESS_TOKEN_EXPIRED));

        for(String method : HttpBuilder.HTTP_METHODS) {
            Map<String, Object> options = this.getOAuthOptions(method);
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_OAUTH_EXPIRED_URL);

            this.plugin.executeStep(pluginContext, options);
        }
    }

    @Test(expected = StepException.class)
    public void cannotCallOAuthEndpointWithCredentials() throws StepException {
        Map<String, Object> options = this.getOAuthOptions("GET");
        options.put("username", OAuthClientTest.CLIENT_INVALID);
        options.put("password", OAuthClientTest.CLIENT_SECRET);

        this.plugin.executeStep(pluginContext, options);
    }

    @Test(expected = StepException.class)
    public void canHandle500ErrorWithOAuth() throws StepException {
        Map<String, Object> options = getOAuthOptions("GET");

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_500);

        this.plugin.executeStep(pluginContext, options);
    }

    @Test
    public void canPrintNoContent() throws StepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + NO_CONTENT_URL);
        options.put("method", "GET");
        options.put("printResponse",true);
        options.put("printResponseToFile",false);

        this.plugin.executeStep(pluginContext, options);
    }
}
