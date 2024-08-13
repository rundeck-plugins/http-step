package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.FrameworkProject;
import com.dtolabs.rundeck.core.common.FrameworkProjectMgr;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.ExecutionLogger;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.utils.IPropertyLookup;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import edu.ohio.ais.rundeck.util.OAuthClientTest;
import org.apache.tools.ant.util.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

public class HttpWorkflowNodeStepPluginTest {
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

    protected HttpWorkflowNodeStepPlugin plugin;
    protected OAuthClientTest oAuthClientTest = new OAuthClientTest();

    protected Map<String, Object> configuration;
    protected Map<String, Map<String, String>> dataContext;
    protected PluginStepContext pluginContext;
    protected PluginLogger pluginLogger;
    protected INodeEntry node;
    protected File resourcePath = new File("src" + File.separator + "test" + File.separator + "resources");
    protected File testResource = new File(resourcePath + File.separator + "example.json");

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

    private static String readFileAsString(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes);
    }

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(18089);

    @Before
    public void setUp() {
        plugin = new HttpWorkflowNodeStepPlugin();
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

        node = Mockito.mock(INodeEntry.class);
        pluginLogger = Mockito.mock(PluginLogger.class);
        pluginContext = Mockito.mock(PluginStepContext.class);
        ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);
        when(executionContext.getExecutionLogger()).thenReturn(pluginLogger);
        when(pluginContext.getLogger()).thenReturn(pluginLogger);
        when(pluginContext.getExecutionContext()).thenReturn(executionContext);

        // Mock the necessary objects
        Framework framework = Mockito.mock(Framework.class);
        FrameworkProjectMgr frameworkProjectMgr = Mockito.mock(FrameworkProjectMgr.class);
        FrameworkProject frameworkProject = Mockito.mock(FrameworkProject.class);
        IPropertyLookup frameworkProperties = Mockito.mock(IPropertyLookup.class);

        // Mock the interactions
        when(pluginContext.getFramework()).thenReturn(framework);
        when(framework.getFrameworkProjectMgr()).thenReturn(frameworkProjectMgr);
        when(frameworkProjectMgr.getFrameworkProject(anyString())).thenReturn(frameworkProject);
        when(frameworkProject.getProperties()).thenReturn(new HashMap<String, String>());
        when(framework.getPropertyLookup()).thenReturn(frameworkProperties);
        when(frameworkProperties.hasProperty(anyString())).thenReturn(true);

        dataContext =new HashMap<>();
        when(pluginContext.getDataContext()).thenReturn(dataContext);

    }

    @After
    public void tearDown(){
        if( Files.exists(this.testResource.toPath()) ){
            FileUtils.delete(this.testResource);
        }
    }

    @Test()
    public void canGetPluginDescription() {
        Description description = this.plugin.getDescription();

        assertEquals(description.getName(), HttpWorkflowNodeStepPlugin.SERVICE_PROVIDER_NAME);
    }

    @Test()
    public void canValidateConfiguration() {
        Map<String, Object> options = new HashMap<>();

        try {
            this.plugin.executeNodeStep(pluginContext, options, node );
            fail("Expected configuration exception.");
        } catch (NodeStepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }

        options.put("remoteUrl", REMOTE_URL);
        options.put("method", "GET");
        options.put("authentication", HttpBuilder.AUTH_BASIC);
        options.put("printResponseCode", "true");

        try {
            this.plugin.executeNodeStep(pluginContext, options, node );
            fail("Expected configuration exception.");
        } catch (NodeStepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }

        options.put("authentication", HttpBuilder.AUTH_OAUTH2);

        try {
            this.plugin.executeNodeStep(pluginContext, options, node );
            fail("Expected configuration exception.");
        } catch (NodeStepException se) {
            assertEquals(se.getFailureReason(), StepFailureReason.ConfigurationFailure);
        }
    }

    @Test()
    public void canCallSimpleEndpoint() throws NodeStepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            this.plugin.executeNodeStep(pluginContext, this.getExecutionOptions(method), node );
        }
    }

    @Test()
    public void canSetCustomTimeout() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_URL);
        options.put("method", "GET");
        options.put("timeout", REQUEST_TIMEOUT);

        this.plugin.executeNodeStep(pluginContext, options, node );

        try {
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_SLOW_URL);
            this.plugin.executeNodeStep(pluginContext, options, node );
            fail("Expected exception " + NodeStepException.class.getCanonicalName() + " not thrown.");
        } catch(NodeStepException se) {}

        options.put("timeout", SLOW_TIMEOUT + 1000);
        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test()
    public void canCallBasicEndpoint() throws NodeStepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            Map<String, Object> options = this.getBasicOptions(method);
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_BASIC_URL);
            options.put("printResponseCode", "true");

            this.plugin.executeNodeStep(pluginContext, options, node );
        }
    }

    @Test(expected = NodeStepException.class)
    public void canHandle500Error() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_500);
        options.put("printResponseCode", "true");
        options.put("method", "GET");

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test(expected = NodeStepException.class)
    public void canHandleBadUrl() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + BOGUS_URL);
        options.put("method", "GET");

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test(expected = NodeStepException.class)
    public void canHandleBadHost() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", "http://neverGoingToBe.aProperUrl/bogus");
        options.put("method", "GET");

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test(expected = NodeStepException.class)
    public void canHandleBASICWrongAuthType() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_401);
        options.put("method", "GET");
        options.put("username", OAuthClientTest.CLIENT_VALID);
        options.put("password", OAuthClientTest.CLIENT_SECRET);
        options.put("authentication", HttpBuilder.AUTH_BASIC);

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test(expected = NodeStepException.class)
    public void canHandleAuthenticationRequired() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_401);
        options.put("method", "GET");
        options.put("authentication", HttpBuilder.AUTH_BASIC);

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test()
    public void canCallOAuthEndpoint() throws NodeStepException {
        for(String method : HttpBuilder.HTTP_METHODS) {
            this.plugin.executeNodeStep(pluginContext, this.getOAuthOptions(method), node );
        }
    }

    @Test()
    public void canCallOAuthEndpointWithExpiredToken() throws NodeStepException {
        this.plugin.oauthClients.put(OAUTH_CLIENT_MAP_KEY, this.oAuthClientTest.setupClient(OAuthClientTest.ACCESS_TOKEN_EXPIRED));

        for(String method : HttpBuilder.HTTP_METHODS) {
            Map<String, Object> options = this.getOAuthOptions(method);
            options.put("remoteUrl", OAuthClientTest.BASE_URI + REMOTE_OAUTH_EXPIRED_URL);

            this.plugin.executeNodeStep(pluginContext, options, node );
        }
    }

    @Test(expected = NodeStepException.class)
    public void cannotCallOAuthEndpointWithCredentials() throws NodeStepException {
        Map<String, Object> options = this.getOAuthOptions("GET");
        options.put("username", OAuthClientTest.CLIENT_INVALID);
        options.put("password", OAuthClientTest.CLIENT_SECRET);

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test(expected = NodeStepException.class)
    public void canHandle500ErrorWithOAuth() throws NodeStepException {
        Map<String, Object> options = getOAuthOptions("GET");

        options.put("remoteUrl", OAuthClientTest.BASE_URI + ERROR_URL_500);

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test
    public void canPrintNoContent() throws NodeStepException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + NO_CONTENT_URL);
        options.put("method", "GET");
        options.put("printResponse",true);
        options.put("printResponseToFile",false);

        this.plugin.executeNodeStep(pluginContext, options, node );
    }

    @Test
    public void canPrintContentToFile() throws NodeStepException, IOException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + NO_CONTENT_URL);
        options.put("method", "GET");
        options.put("printResponse",true);
        options.put("printResponseToFile",true);
        options.put("file", testResource);

        assertNotNull(Paths.get(testResource.toString()));
        this.plugin.executeNodeStep(pluginContext, options, node );
        assertNotNull(readFileAsString(testResource.toString()));
    }

    @Test
    public void canPrintContentToFileIfPrintResponseIsFalse() throws NodeStepException, IOException {
        Map<String, Object> options = new HashMap<>();

        options.put("remoteUrl", OAuthClientTest.BASE_URI + NO_CONTENT_URL);
        options.put("method", "GET");
        options.put("printResponse",false);
        options.put("printResponseToFile",true);
        options.put("file", testResource);

        assertNotNull(Paths.get(testResource.toString()));
        this.plugin.executeNodeStep(pluginContext, options, node );
        assertNotNull(readFileAsString(testResource.toString()));
    }
}
