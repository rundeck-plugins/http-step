package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.proxy.ProxySecretBundleCreator;
import com.dtolabs.rundeck.core.execution.proxy.SecretBundle;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;
import edu.ohio.ais.rundeck.util.OAuthClient;
import edu.ohio.ais.rundeck.util.SecretBundleUtil;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ByteArrayEntity;

import java.io.*;
import java.util.*;

import static edu.ohio.ais.rundeck.HttpBuilder.propertyResolver;


/**
 * Main implementation of the plugin. This will handle fetching
 * tokens when they're expired and sending the appropriate request.
 */
@Plugin(name = HttpWorkflowStepPlugin.SERVICE_PROVIDER_NAME, service = ServiceNameConstants.WorkflowStep)
public class HttpWorkflowStepPlugin implements StepPlugin, Describable, ProxySecretBundleCreator {

    /**
     * Maximum number of attempts with which to try the request.
     */
    public static final Integer MAX_ATTEMPTS = 5;

    /**
     * Default request timeout for execution. This only times out the
     * request for the URL, not OAuth authentication.
     */
    public static final Integer DEFAULT_TIMEOUT = 30*1000;

    public static final String SERVICE_PROVIDER_NAME = "edu.ohio.ais.rundeck.HttpWorkflowStepPlugin";


    /**
     * Synchronized map of all existing OAuth clients. This is indexed by
     * the Client ID and the token URL so that we can store and re-use access tokens.
     */
    final Map<String, OAuthClient> oauthClients = Collections.synchronizedMap(new HashMap<String, OAuthClient>());


    /**
     * Setup our plugin description, including all of the various configurable
     * options.
     *
     * @see <a href="http://rundeck.org/docs/developer/plugin-development.html#plugin-descriptions">Plugin Descriptions</a>
     *
     * @return The plugin description
     */
    @Override
    public Description getDescription() {
        return new HttpDescription(SERVICE_PROVIDER_NAME, "HTTP Request Step", "Performs an HTTP request with or without authentication").getDescription();
    }

    @Override
    public void executeStep(PluginStepContext pluginStepContext, Map<String, Object> options) throws StepException {
        PluginLogger log = pluginStepContext.getLogger();

        Description description = new HttpDescription(SERVICE_PROVIDER_NAME, "HTTP Request Node Step", "Performs an HTTP request with or without authentication (per node)").getDescription();
        description.getProperties().forEach(prop->
                propertyResolver("WorflowStep",prop.getName(), options, pluginStepContext, SERVICE_PROVIDER_NAME)
        );

        // Parse out the options
        String remoteUrl = options.containsKey("remoteUrl") ? options.get("remoteUrl").toString() : null;
        String method = options.containsKey("method") ? options.get("method").toString() : null;
        Integer timeout = options.containsKey("timeout") ? Integer.parseInt(options.get("timeout").toString()) : DEFAULT_TIMEOUT;
        String headers = options.containsKey("headers") ? options.get("headers").toString() : null;
        String body = options.containsKey("body") ? options.get("body").toString() : null;

        if(remoteUrl == null || method == null) {
            throw new StepException("Remote URL and Method are required.", StepFailureReason.ConfigurationFailure);
        }

        //Use options in remote URL
        if (null != remoteUrl && remoteUrl.contains("${")) {
            remoteUrl = DataContextUtils.replaceDataReferences(remoteUrl, pluginStepContext.getDataContext());
        }

        //Use options in body
        if (null != body && body.contains("${")) {
            body = DataContextUtils.replaceDataReferences(body, pluginStepContext.getDataContext());
        }

        HttpBuilder builder = new HttpBuilder();
        builder.setLog(log);
        builder.setMaxAttempts(MAX_ATTEMPTS);
        builder.setOauthClients(oauthClients);

        // Setup the request and process it.
        RequestBuilder request = RequestBuilder.create(method)
                .setUri(remoteUrl)
                .setConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build());

        log.log(5,"Creating HTTP " + request.getMethod() + " request to " + request.getUri());

        String authHeader = builder.getAuthHeader(pluginStepContext, options);

        if(authHeader != null) {
            log.log(5,"Authentication header set to " + authHeader);
            request.setHeader("Authorization", authHeader);
        }

        //add custom headers, it could be json or yml
        if(headers !=null){
            builder.setHeaders(headers, request);
        }

        //send body
        if(body !=null){
            HttpEntity entity = null;
            try {
                entity = new ByteArrayEntity(body.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            request.setEntity(entity);
        }

        builder.doRequest(options, request.build(), 1);
    }

    @Override
    public SecretBundle prepareSecretBundleWorkflowStep(ExecutionContext context, Map<String, Object> configuration) {
        return SecretBundleUtil.getSecrets(context, configuration);
    }

    @Override
    public List<String> listSecretsPathWorkflowStep(ExecutionContext context, Map<String, Object> configuration) {
        return SecretBundleUtil.getListSecrets(configuration);
    }

}
