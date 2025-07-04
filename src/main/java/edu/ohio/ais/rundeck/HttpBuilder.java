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


    public CloseableHttpClient getHttpClient(Map<String, Object> options) throws GeneralSecurityException, StepException {
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();

        httpClientBuilder.disableAuthCaching();
        httpClientBuilder.disableAutomaticRetries();


        if(!getBooleanOption(options, "sslVerify", true)) {
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
        if(getBooleanOption(options, "useSystemProxySettings", false) && !getBooleanOption(options, "proxySettings", false)) {
            log.log(5, "Using proxy settings set on system");
            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            if (proxyPort.isEmpty() || proxyHost.isEmpty()) {
                throw new StepException("proxyHost and proxyPort are required to use System Proxy Settings", StepFailureReason.ConfigurationFailure);
            }
            HttpHost proxy = new HttpHost(proxyHost, Integer.parseInt(proxyPort), "http");
            httpClientBuilder.setProxy(proxy);
        }
        if (getBooleanOption(options, "proxySettings", false)) {
            String proxyIP = getStringOption(options, "proxyIP", "");
            String proxyPort = getStringOption(options, "proxyPort", "");

            if (proxyIP.isEmpty() || proxyPort.isEmpty()) {
                throw new StepException("Proxy IP and Proxy Port are required to use Proxy Settings.", StepFailureReason.ConfigurationFailure);
            }

            log.log(5, "proxy IP set in job: " + proxyIP);
            log.log(5, "proxy Port set in job: " + proxyPort);
            HttpHost proxy = new HttpHost(proxyIP, Integer.parseInt(proxyPort), "http");
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

            if(getBooleanOption(options,"printResponseCode",false)) {
                String responseCode = response.getStatusLine().toString();
                log.log(2, "Response Code: " + responseCode);
            }

            //print the response content
            if(getBooleanOption(options,"printResponse",false)) {
                output = getOutputForResponse(this.prettyPrint(response));
                //print response
                log.log(2, output);
            }

            if(getBooleanOption(options,"printResponseToFile",false)){
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
            int actualCode = response.getStatusLine().getStatusCode();
            String responseCodeStr = getStringOption(options, "responseCode");
            validateResponseCodeOrThrow(response, actualCode, responseCodeStr);

            // Sometimes we may need to refresh our OAuth token.
            if(response.getStatusLine().getStatusCode() == OAuthClient.STATUS_AUTHORIZATION_REQUIRED) {
                log.log(5,"Warning: Got authorization required exception from " + request.getURI());

                // But only if we actually use OAuth for authentication
                if(options.containsKey("authentication")) {
                    if(AUTH_BASIC.equals(options.get("authentication"))) { // comparing this way avoids possible NPEs
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
                responseCodeStr = getStringOption(options, "responseCode");
                validateResponseCodeOrThrow(response, actualCode, responseCodeStr);

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
        String authentication = getStringOption(options, "authentication",AUTH_NONE);
        //moving the password to the key storage
        String password=null;
        String authHeader = null;


        if(options.containsKey("password") ){
            String passwordRaw = getStringOption(options, "password");
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
            String username = getStringOption(options, "username");

            if(username == null || password == null) {
                throw new StepException("Username and password not provided for BASIC Authentication",
                        StepFailureReason.ConfigurationFailure);
            }

            authHeader = username + ":" + password;

            //As per RFC2617 the Basic Authentication standard has to send the credentials Base64 encoded.
            authHeader = "Basic " + com.dtolabs.rundeck.core.utils.Base64.encode(authHeader);
        } else if (authentication.equals(AUTH_OAUTH2)) {
            // Get an OAuth token and setup the auth header for OAuth
            String tokenEndpoint = getStringOption(options, "oauthTokenEndpoint");
            String validateEndpoint = getStringOption(options, "oauthValidateEndpoint");
            String clientId = getStringOption(options, "username");



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

    /**
     * Retrieves a string value from the options map.
     * If the key does not exist or the value is null, it returns null.
     *
     * @param options the map containing option keys and values
     * @param key     the key whose associated value is to be returned
     * @return the string value associated with the specified key, or null if the key does not exist or the value is null
     */
    static String getStringOption(Map<String, Object> options, String key) {
        return getStringOption(options, key, null);
    }

    /**
     * Retrieves a string value from the options map.
     * If the key does not exist or the value is null, it returns the specified default value.
     *
     * @param options  the map containing option keys and values
     * @param key      the key whose associated value is to be returned
     * @param defValue the default value to return if the key does not exist or the value is null
     * @return the string value associated with the specified key, or the default value if the key does not exist or the value is null
     */
    static String getStringOption(Map<String, Object> options, String key, String defValue) {
        return options.containsKey(key) && options.get(key) != null ? options.get(key).toString() : defValue;
    }

    /**
     * Retrieves an integer value from the options map.
     * If the key does not exist or the value is null, it returns the specified default value.
     *
     * @param options  the map containing option keys and values
     * @param key      the key whose associated value is to be returned
     * @param defValue the default value to return if the key does not exist or the value is null
     * @return the integer value associated with the specified key, or the default value if the key does not exist or the value is null
     * @throws NumberFormatException if the value cannot be parsed as an integer
     */
    public static Integer getIntOption(Map<String, Object> options, String key, Integer defValue) {
        return options.containsKey(key) && options.get(key) != null ? Integer.parseInt(options.get(key).toString()) : defValue;
    }

    /**
     * Retrieves a boolean value from the options map.
     * If the key does not exist or the value is null, it returns the specified default value.
     *
     * @param options  the map containing option keys and values
     * @param key      the key whose associated value is to be returned
     * @param defValue the default value to return if the key does not exist or the value is null
     * @return the boolean value associated with the specified key, or the default value if the key does not exist or the value is null
     */
    public static Boolean getBooleanOption(Map<String, Object> options, String key, Boolean defValue) {
        return options.containsKey(key) && options.get(key) != null ? Boolean.parseBoolean(options.get(key).toString()) : defValue;
    }

    /**
     * Checks whether the given actual HTTP status code matches any of the values or patterns
     * defined in the responseCode string.
     *
     * <p>The responseCode string may contain:</p>
     * <ul>
     *   <li>Single codes (e.g., "200")</li>
     *   <li>Ranges (e.g., "200-206")</li>
     *   <li>Wildcard groups (e.g., "2xx", "4xx")</li>
     *   <li>Comma-separated combinations of any of the above (e.g., "200,204-206,2xx")</li>
     * </ul>
     *
     * Malformed entries are ignored with a warning log.
     *
     * @param actualCode the HTTP response code returned by the server
     * @param responseCodeStr the expected response codes or patterns, as a comma-separated string
     * @return true if the actualCode matches any pattern or value in responseCodeStr; false otherwise
     */
    public static boolean isExpectedResponseCode(int actualCode, String responseCodeStr) {
        if (responseCodeStr == null || responseCodeStr.trim().isEmpty()) {
            return false;
        }

        for (String codePattern : responseCodeStr.split(",")) {
            codePattern = codePattern.trim();

            try {
                if (codePattern.matches("\\d{3}")) {
                    if (actualCode == Integer.parseInt(codePattern)) {
                        return true;
                    }
                } else if (codePattern.matches("\\d{3}-\\d{3}")) {
                    String[] parts = codePattern.split("-");
                    int start = Integer.parseInt(parts[0]);
                    int end = Integer.parseInt(parts[1]);
                    if (actualCode >= start && actualCode <= end) {
                        return true;
                    }
                } else if (codePattern.matches("\\dxx")) {
                    int hundreds = Integer.parseInt(codePattern.substring(0, 1)) * 100;
                    if (actualCode >= hundreds && actualCode < hundreds + 100) {
                        return true;
                    }
                }
            } catch (NumberFormatException ex) {
                // Log at a low level
                System.err.println("Warning: Ignoring malformed responseCode entry: '" + codePattern + "'");
            }
        }

        return false;
    }



    /**
     * Validates the HTTP response code against a list or range of acceptable values.
     *
     * <p>If a responseCode string is provided (e.g. "200,204-206,2xx"), this method checks if
     * the actual response code matches any of the defined values or patterns. If it does not,
     * a StepException is thrown.</p>
     *
     * <p>If no responseCode is provided, the default behavior is to fail on any code
     * greater than or equal to 400.</p>
     *
     * @param response the {@link HttpResponse} returned by the HTTP client
     * @param actualCode the actual HTTP status code from the response
     * @param responseCodeStr the user-defined expected response code(s), which may include comma-separated values, ranges (e.g. "200-206"), or wildcard groups (e.g. "2xx").
     * @throws IOException if an error occurs reading the response body
     * @throws StepException if the response code is unexpected or represents a failure
     */
    private void validateResponseCodeOrThrow(HttpResponse response, int actualCode, String responseCodeStr) throws IOException, StepException {
        if (responseCodeStr != null && !responseCodeStr.trim().isEmpty()) {
            if (!isExpectedResponseCode(actualCode, responseCodeStr)) {
                String message = "Unexpected response code: " + actualCode;
                String body = EntityUtils.toString(response.getEntity());
                if (!body.isEmpty()) {
                    message += ": " + body;
                }
                throw new StepException(message, Reason.HTTPFailure);
            }
        } else {
            if (actualCode >= 400) {
                String message = "HTTP request failed with status code: " + actualCode;
                String body = EntityUtils.toString(response.getEntity());
                if (!body.isEmpty()) {
                    message += ": " + body;
                }
                throw new StepException(message, Reason.HTTPFailure);
            }
        }
    }



}
