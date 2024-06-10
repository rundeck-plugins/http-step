package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.utils.IPropertyLookup;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import edu.ohio.ais.rundeck.util.OAuthClient;
import edu.ohio.ais.rundeck.util.SecretBundleUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.dom4j.DocumentHelper;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.ProxySelector;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class HttpBuilder {
    public static final String AUTH_NONE = "None";
    public static final String AUTH_BASIC = "Basic";
    public static final String AUTH_OAUTH2 = "OAuth 2.0";
    public static final String XML_FORMAT = "xml";
    public static final String JSON_FORMAT = "json";
    public static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};

    private Integer maxAttempts = 5;
    private PluginLogger log;

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public PluginLogger getLog() {
        return log;
    }

    public void setLog(PluginLogger log) {
        this.log = log;
    }

    public Map<String, OAuthClient> getOauthClients() {
        return oauthClients;
    }


    public void setOauthClients(Map<String, OAuthClient> oauthClients) {
        this.oauthClients = oauthClients;
    }

    /**
     * Synchronized map of all existing OAuth clients. This is indexed by
     * the Client ID and the token URL so that we can store and re-use access tokens.
     */
    Map<String, OAuthClient> oauthClients = Collections.synchronizedMap(new HashMap<String, OAuthClient>());

    public enum Reason implements FailureReason {
        OAuthFailure,   // Failure from the OAuth protocol
        HTTPFailure     // Any HTTP related failures.
    }


    public CloseableHttpClient getHttpClient(Map<String, Object> options) throws GeneralSecurityException {
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();

        httpClientBuilder.disableAuthCaching();
        httpClientBuilder.disableAutomaticRetries();

        if(options.containsKey("sslVerify") && !Boolean.parseBoolean(options.get("sslVerify").toString())) {
            log.log(5,"Disabling all SSL certificate verification.");
            SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
            sslContextBuilder.loadTrustMaterial(null, new TrustStrategy() {
                @Override
                public boolean isTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                    return true;
                }
            });

            httpClientBuilder.setSSLHostnameVerifier(new NoopHostnameVerifier());
            httpClientBuilder.setSSLContext(sslContextBuilder.build());
        }
        if(options.get("useSystemProxySettings").equals("true")) {

            log.log(5, "Using proxy settings set on system");

            httpClientBuilder.setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()));

        }
        if(options.containsKey("proxySettings") && Boolean.parseBoolean(options.get("proxySettings").toString())){
            log.log(5, "proxy IP set in job: " + options.get("proxyIP").toString());

            HttpHost proxy = new HttpHost(options.get("proxyIP").toString(), Integer.valueOf((String)options.get("proxyPort")), "http");
            httpClientBuilder.setProxy(proxy);
        }

        return httpClientBuilder.build();
    }

    /**
     * Execute a single request. This will call itself if it needs to refresh an OAuth token.
     *
     * @param options All of the options provided to the plugin execution
     * @param request The HTTP request we're supposed to execute
     * @param attempts The attempt number
     * @throws StepException Thrown when any error occurs
     */
    public void doRequest(Map<String, Object> options, HttpUriRequest request, Integer attempts) throws StepException {
        if(attempts > this.maxAttempts) {
            throw new StepException("Unable to complete request after maximum number of attempts.", StepFailureReason.IOFailure);
        }
        CloseableHttpResponse response = null;
        String output = "";
        try {
            response = this.getHttpClient(options).execute(request);

            if(options.containsKey("printResponseCode") && Boolean.parseBoolean(options.get("printResponseCode").toString())) {
                String responseCode = response.getStatusLine().toString();
                log.log(2, "Response Code: " + responseCode);
            }

            //print the response content
            if(options.containsKey("printResponse") && Boolean.parseBoolean(options.get("printResponse").toString())) {
                output = getOutputForResponse(this.prettyPrint(response));
                //print response
                log.log(2, output);
            }

            if(options.containsKey("printResponseToFile") && Boolean.parseBoolean(options.get("printResponseToFile").toString())){
                File file = new File(options.get("file").toString());
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                if( output.isEmpty() ){
                    output = getOutputForResponse(this.prettyPrint(response));
                }

                writer.write (output);

                //Close writer
                writer.close();
            }

            //check response status
            if(options.containsKey("checkResponseCode") && Boolean.parseBoolean(options.get("checkResponseCode").toString())) {

                if(options.containsKey("responseCode")){
                    int responseCode = Integer.valueOf( (String) options.get("responseCode"));

                    if(response.getStatusLine().getStatusCode()!=responseCode){
                        String message = "Error, the expected response code didn't fix, the value expected was " + responseCode + " and the response code was " +  response.getStatusLine().getStatusCode();
                        throw new StepException(message, HttpBuilder.Reason.HTTPFailure);
                    }

                }

            }

            // Sometimes we may need to refresh our OAuth token.
            if(response.getStatusLine().getStatusCode() == OAuthClient.STATUS_AUTHORIZATION_REQUIRED) {
                log.log(5,"Warning: Got authorization required exception from " + request.getURI());

                // But only if we actually use OAuth for authentication
                if(options.containsKey("authentication")) {
                    if(options.get("authentication").toString().equals(AUTH_BASIC)) {
                        throw new StepException("Remote URL requires authentication but does not support BASIC.", StepFailureReason.ConfigurationFailure);
                    } else if(options.get("authentication").toString().equals(AUTH_OAUTH2)) {
                        log.log(5,"Attempting to refresh OAuth token and try again...");
                        String accessToken;

                        // Another thread might be trying to do the same thing.
                        synchronized(this.oauthClients) {
                            String clientKey = options.get("username").toString() + "@" + options.get("oauthTokenEndpoint").toString();

                            OAuthClient client = this.oauthClients.get(clientKey);
                            client.invalidateAccessToken();

                            try {
                                accessToken = client.getAccessToken();
                            } catch(Exception e) {
                                StepException se = new StepException("Error refreshing OAuth Access Token: " + e.getMessage(),
                                        HttpBuilder.Reason.OAuthFailure);
                                se.initCause(e);
                                throw se;
                            }

                            // Don't forget to update the client map in case something changed
                            this.oauthClients.put(clientKey, client);
                        }

                        // Build a new request and call `doRequest` again.
                        request.setHeader("Authorization", "Bearer " + accessToken);

                        log.log(5,"Authentication header set to Bearer " + accessToken);

                        this.doRequest(options, request, attempts + 1);
                    } else {
                        throw new StepException("Remote URL requires authentication.", StepFailureReason.ConfigurationFailure);
                    }
                } else {
                    throw new StepException("Remote URL requires authentication.", StepFailureReason.ConfigurationFailure);
                }
            } else if(response.getStatusLine().getStatusCode() >= 400) {
                String message = "Error when sending request";

                if(response.getStatusLine().getReasonPhrase().length() > 0) {
                    message += ": " + response.getStatusLine().getReasonPhrase();
                } else {
                    message += ": " + Integer.toString(response.getStatusLine().getStatusCode()) + " Error";
                }

                String body = EntityUtils.toString(response.getEntity());
                if(body.length() > 0) {
                    message += ": " + body;
                }

                throw new StepException(message, HttpBuilder.Reason.HTTPFailure);
            }
        } catch (IOException e) {
            StepException ese = new StepException("Error when sending request: " + e.getMessage(), HttpBuilder.Reason.HTTPFailure);
            ese.initCause(e);
            throw ese;
        } catch (GeneralSecurityException se) {
            StepException sse = new StepException("Error when sending request: " + se.getMessage(), HttpBuilder.Reason.HTTPFailure);
            se.initCause(se);
            throw sse;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private String getOutputForResponse(String printer){
        return !printer.isEmpty() ? printer : "";
    }

    private StringBuffer getPageContent(HttpResponse response) {

        BufferedReader rd = null;
        HttpEntity reponseEntity = response.getEntity();
        StringBuffer result = new StringBuffer();

        if ( reponseEntity != null ) {
            try {
                rd = new BufferedReader(new InputStreamReader(reponseEntity.getContent()));
                String line = "";
                while ((line = rd.readLine()) != null) {
                    result.append(line);
                    result.append(System.getProperty("line.separator"));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (rd != null) {
                    try {
                        rd.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        return result;
    }

    //print response
    public String prettyPrint(HttpResponse response){

        HttpEntity entity = response.getEntity();
        ContentType contentType;
        String mimeType="";
        if (entity != null) {
            contentType = ContentType.get(entity);

            if(contentType!=null) {
                mimeType = contentType.getMimeType();
            }
        }

        String outputWithoutFormat=getPageContent(response).toString();

        String output = "";

        if(mimeType.contains(JSON_FORMAT) || mimeType.contains(XML_FORMAT)) {

            if (mimeType.contains(JSON_FORMAT)) {
                output = new GsonBuilder().setPrettyPrinting().create().toJson(new JsonParser().parse(outputWithoutFormat));
            }

            if (mimeType.contains(XML_FORMAT)) {
                StringWriter sw;

                try {
                    final OutputFormat format = OutputFormat.createPrettyPrint();
                    final org.dom4j.Document document = DocumentHelper.parseText(outputWithoutFormat);
                    sw = new StringWriter();
                    final XMLWriter writer = new XMLWriter(sw, format);
                    writer.write(document);
                }
                catch (Exception e) {
                    throw new RuntimeException("Error pretty printing xml:\n" + outputWithoutFormat, e);
                }

                output = sw.toString();
            }
        }else{
            output=outputWithoutFormat;
        }

        return output;
    }



    String getAuthHeader(PluginStepContext pluginStepContext,  Map<String, Object> options) throws StepException {
        String authentication = options.containsKey("authentication") ? options.get("authentication").toString() : AUTH_NONE;
        //moving the password to the key storage
        String password=null;
        String authHeader = null;


        if(options.containsKey("password") ){
            String passwordRaw = options.containsKey("password") ? options.get("password").toString() : null;
            //to avid the test error add a try-catch
            //if it didn't find the key path, it will use the password directly
            byte[] content = SecretBundleUtil.getStoragePassword(pluginStepContext.getExecutionContext(),passwordRaw );
            if(content!=null){
                password = new String(content);
            }
            if(password==null){
                password=passwordRaw;
            }
        }

        if(authentication.equals(AUTH_BASIC)) {
            // Setup the authentication header for BASIC
            String username = options.containsKey("username") ? options.get("username").toString() : null;

            if(username == null || password == null) {
                throw new StepException("Username and password not provided for BASIC Authentication",
                        StepFailureReason.ConfigurationFailure);
            }

            authHeader = username + ":" + password;

            //As per RFC2617 the Basic Authentication standard has to send the credentials Base64 encoded.
            authHeader = "Basic " + com.dtolabs.rundeck.core.utils.Base64.encode(authHeader);
        } else if (authentication.equals(AUTH_OAUTH2)) {
            // Get an OAuth token and setup the auth header for OAuth
            String tokenEndpoint = options.containsKey("oauthTokenEndpoint") ? options.get("oauthTokenEndpoint").toString() : null;
            String validateEndpoint = options.containsKey("oauthValidateEndpoint") ? options.get("oauthValidateEndpoint").toString() : null;
            String clientId = options.containsKey("username") ? options.get("username").toString() : null;
            String clientSecret = password;


            if(tokenEndpoint == null) {
                throw new StepException("Token endpoint not provided for OAuth 2.0 Authentication.",
                        StepFailureReason.ConfigurationFailure);
            }

            String clientKey = clientId + "@" + tokenEndpoint;
            String accessToken;

            // Another thread may be trying to do the same thing.
            synchronized(this.oauthClients) {
                OAuthClient client;

                if(this.oauthClients.containsKey(clientKey)) {
                    // Update the existing client with our options if it exists.
                    // We do this so that changes to configuration will always
                    // update clients on next run.
                    log.log(5,"Found existing OAuth client with key " + clientKey);
                    client = this.oauthClients.get(clientKey);
                    client.setCredentials(clientId, clientSecret);
                    client.setValidateEndpoint(validateEndpoint);
                } else {
                    // Create a brand new client
                    log.log(5,"Creating new OAuth client with key " + clientKey);
                    client = new OAuthClient(OAuthClient.GrantType.CLIENT_CREDENTIALS, log);
                    client.setCredentials(clientId, clientSecret);
                    client.setTokenEndpoint(tokenEndpoint);
                    client.setValidateEndpoint(validateEndpoint);
                }

                // Grab the access token
                try {
                    log.log(5,"Attempting to fetch access token...");
                    accessToken = client.getAccessToken();
                } catch(Exception ex) {
                    StepException se = new StepException("Error obtaining OAuth Access Token: " + ex.getMessage(),
                            HttpBuilder.Reason.OAuthFailure);
                    se.initCause(ex);
                    throw se;
                }

                this.oauthClients.put(clientKey, client);
            }

            authHeader = "Bearer " + accessToken;
        }

        return authHeader;
    }


    public void setHeaders(String headers, RequestBuilder request){
        //checking json
        Gson gson = new Gson();
        Map<String,String> map = new HashMap<>();

        try {
            map = (Map<String,String>) gson.fromJson(headers, map.getClass());
        } catch (Exception e) {
            map = null;
        }

        //checking yml
        if(map == null) {
            map = new HashMap<>();
            Object object = null;
            try {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                map = yaml.load(headers);
            } catch (Exception e) {
                map = null;
            }
        }

        if(map == null){
            log.log(0, "Error parsing the headers");
        }else{
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                request.setHeader(key, value);
            }
        }
    }

    static void propertyResolver(String pluginType, String property, Map<String,Object> Configuration, PluginStepContext context, String SERVICE_PROVIDER_NAME) {

        String projectPrefix = "project.plugin." + pluginType + "." + SERVICE_PROVIDER_NAME + ".";
        String frameworkPrefix = "framework.plugin." + pluginType + "." + SERVICE_PROVIDER_NAME + ".";

        Map<String,String> projectProperties = context.getFramework().getFrameworkProjectMgr().getFrameworkProject(context.getFrameworkProject()).getProperties();
        IPropertyLookup frameworkProperties = context.getFramework().getPropertyLookup();

        if(!Configuration.containsKey(property) && projectProperties.containsKey(projectPrefix + property)) {

            Configuration.put(property, projectProperties.get(projectPrefix + property));

        } else if (!Configuration.containsKey(property) && frameworkProperties.hasProperty(frameworkPrefix + property)) {

            Configuration.put(property, frameworkProperties.getProperty(frameworkPrefix + property));

        }
    }

}
