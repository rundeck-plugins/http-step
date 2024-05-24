package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.proxy.ProxySecretBundleCreator;
import com.dtolabs.rundeck.core.execution.proxy.SecretBundle;
import com.dtolabs.rundeck.core.execution.utils.ResolverUtil;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.utils.IPropertyLookup;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.step.NodeStepPlugin;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import edu.ohio.ais.rundeck.util.OAuthClient;
import edu.ohio.ais.rundeck.util.SecretBundleUtil;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ByteArrayEntity;

import java.io.UnsupportedEncodingException;
import java.util.*;

@Plugin(name = HttpWorkflowNodeStepPlugin.SERVICE_PROVIDER_NAME, service = ServiceNameConstants.WorkflowNodeStep)
public class HttpWorkflowNodeStepPlugin implements NodeStepPlugin, Describable, ProxySecretBundleCreator {
    public static final String SERVICE_PROVIDER_NAME = "edu.ohio.ais.rundeck.HttpWorkflowNodeStepPlugin";

    /**
     * Maximum number of attempts with which to try the request.
     */
    public static final Integer MAX_ATTEMPTS = 5;

    /**
     * Default request timeout for execution. This only times out the
     * request for the URL, not OAuth authentication.
     */
    public static final Integer DEFAULT_TIMEOUT = 30*1000;

//    @PluginProperty(
//            title = "Proxy URL",
//            description = "HTTP URL to which to make the request.",
//            required = true
//    )
//    String proxyURL;

    /**
     * Synchronized map of all existing OAuth clients. This is indexed by
     * the Client ID and the token URL so that we can store and re-use access tokens.
     */
    final Map<String, OAuthClient> oauthClients = Collections.synchronizedMap(new HashMap<String, OAuthClient>());


    @Override
    public Description getDescription() {
        return new HttpDescription(SERVICE_PROVIDER_NAME, "HTTP Request Node Step", "Performs an HTTP request with or without authentication (per node)").getDescription();
    }


    @Override
    public void executeNodeStep(PluginStepContext context, Map<String, Object> configuration, INodeEntry entry) throws NodeStepException {
        PluginLogger log = context.getLogger();

        Description description = new HttpDescription(SERVICE_PROVIDER_NAME, "HTTP Request Node Step", "Performs an HTTP request with or without authentication (per node)").getDescription();
        description.getProperties().forEach(prop->
            propertyResolver(prop.getName(), configuration, context)
        );
//        propertyResolver("proxyIP", configuration, context);
//        propertyResolver("proxyPort", configuration, context);

        System.out.println("post-resolver proxyIp: " + configuration.get("proxyIP"));
//        System.out.println("proxySettings: " + configuration.get("proxySettings"));

        // Parse out the options
        String remoteUrl = configuration.containsKey("remoteUrl") ? configuration.get("remoteUrl").toString() : null;
        String method = configuration.containsKey("method") ? configuration.get("method").toString() : null;

        Integer timeout = configuration.containsKey("timeout") ? Integer.parseInt(configuration.get("timeout").toString()) : DEFAULT_TIMEOUT;
        String headers = configuration.containsKey("headers") ? configuration.get("headers").toString() : null;
        String body = configuration.containsKey("body") ? configuration.get("body").toString() : null;

        log.log(5, "remoteUrl: " + remoteUrl);
        log.log(5, "method: " + method);
        log.log(5, "headers: " + headers);
        log.log(5, "timeout: " + timeout);

        if(remoteUrl == null || method == null) {
            throw new NodeStepException("Remote URL and Method are required.", StepFailureReason.ConfigurationFailure, entry.getNodename());
        }

        //Use options in remote URL
        if (null != remoteUrl && remoteUrl.contains("${")) {
            remoteUrl = DataContextUtils.replaceDataReferencesInString(remoteUrl, context.getDataContextObject());
        }

        //Use options in body
        if (null != body && body.contains("${")) {
            body = DataContextUtils.replaceDataReferencesInString(body, context.getDataContextObject());
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

        String authHeader = null;
        try {
            authHeader = builder.getAuthHeader(context, configuration);
        } catch (StepException e) {
            throw new NodeStepException(e.getMessage(), e.getFailureReason(), entry.getNodename());
        }

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

        try {
            builder.doRequest(configuration, request.build(), 1);
        } catch (StepException e) {
            throw new NodeStepException(e.getMessage(), e.getFailureReason(), entry.getNodename());
        }

    }

    @Override
    public SecretBundle prepareSecretBundleWorkflowNodeStep(ExecutionContext context, INodeEntry node, Map<String, Object> configuration) {
        return SecretBundleUtil.getSecrets(context, configuration);
    }

    @Override
    public List<String> listSecretsPathWorkflowNodeStep(ExecutionContext context, INodeEntry node, Map<String, Object> configuration) {
        return SecretBundleUtil.getListSecrets(configuration);
    }

    void propertyResolver(String property, Map<String,Object> Configuration, PluginStepContext context) {

        String projectPrefix = "project.plugin.WorkflowNodeStep." + SERVICE_PROVIDER_NAME + ".";
        String frameworkPrefix = "framework.plugin.WorkflowNodeStep" + SERVICE_PROVIDER_NAME + ".";

        Map<String,String> projectProperties = context.getFramework().getFrameworkProjectMgr().getFrameworkProject(context.getFrameworkProject()).getProperties();
        IPropertyLookup frameworkProperties = context.getFramework().getPropertyLookup();

        if(!Configuration.containsKey(property) && projectProperties.containsKey(projectPrefix + property)) {

            Configuration.put(property, projectProperties.get(projectPrefix + property));

        } else if (!Configuration.containsKey(property) && frameworkProperties.hasProperty(frameworkPrefix + property)) {

            Configuration.put(property, frameworkProperties.getProperty(frameworkPrefix + property));

        }
        System.out.println("resolver: " + property + Configuration.get(property));
    }

}
