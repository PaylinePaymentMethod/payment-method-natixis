package com.payline.payment.natixis.utils.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.payline.payment.natixis.bean.business.NatixisBanksResponse;
import com.payline.payment.natixis.bean.business.NatixisErrorResponse;
import com.payline.payment.natixis.bean.business.NatixisPaymentInitResponse;
import com.payline.payment.natixis.bean.business.authorization.JwtUserBody;
import com.payline.payment.natixis.bean.business.authorization.NatixisAuthorizationResponse;
import com.payline.payment.natixis.bean.business.authorization.RFC6749AccessTokenErrorResponse;
import com.payline.payment.natixis.bean.business.fraud.PsuInformation;
import com.payline.payment.natixis.bean.business.payment.Payment;
import com.payline.payment.natixis.bean.configuration.RequestConfiguration;
import com.payline.payment.natixis.exception.InvalidDataException;
import com.payline.payment.natixis.exception.PluginException;
import com.payline.payment.natixis.utils.Constants;
import com.payline.payment.natixis.utils.PluginUtils;
import com.payline.payment.natixis.utils.properties.ConfigProperties;
import com.payline.payment.natixis.utils.security.RSAHolder;
import com.payline.pmapi.bean.common.FailureCause;
import com.payline.pmapi.bean.configuration.PartnerConfiguration;
import com.payline.pmapi.bean.payment.ContractProperty;
import com.payline.pmapi.logger.LogManager;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.logging.log4j.Logger;
import org.tomitribe.auth.signatures.Algorithm;
import org.tomitribe.auth.signatures.Base64;
import org.tomitribe.auth.signatures.Signature;
import org.tomitribe.auth.signatures.Signer;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class NatixisHttpClient {

    private static final Logger LOGGER = LogManager.getLogger(NatixisHttpClient.class);

    private static final String API_AUTH_PATH_OAUTH_TOKEN = "/oauth/token";
    private static final String API_PAYMENT_PATH_BANK_LIST = "/accountServiceProviders";
    private static final String API_PAYMENT_PATH_INIT = "/payment-requests";
    private static final String API_PAYMENT_PATH_STATUS_TOKEN = "{paymentRequestResourceId}";
    private static final String API_PAYMENT_PATH_STATUS = "/payment-requests/" + API_PAYMENT_PATH_STATUS_TOKEN;

    private static final String HTTP_HEADER_X_REQUEST_ID = "X-Request-ID";
    private static final String PSUDATE_HEADER_FORMAT = "yyyyMMddHHmmss";

    private static final String MISSING_PAYMENT_API_URL_ERROR = "Missing payment API base url in partnerConfiguration";

    private static final Algorithm SIGNATURE_ALGORITHM = Algorithm.RSA_SHA256;

    private ConfigProperties config = ConfigProperties.getInstance();

    /**
     * Support for the authorization information.
     */
    private Authorization authorization;

    /**
     * Client used to contact APIs through HTTP.
     */
    private CloseableHttpClient client;

    /**
     * Holder containing the keystore data (keys or certificates).
     */
    private RSAHolder rsaHolder;

    /**
     * The number of time the client must retry to send the request if it doesn't obtain a response.
     */
    private int retries;

    // --- Singleton Holder pattern + initialization BEGIN
    private AtomicBoolean initialized = new AtomicBoolean();
    NatixisHttpClient(){
    }
    private static class Holder {
        private static final NatixisHttpClient instance = new NatixisHttpClient();
    }
    public static NatixisHttpClient getInstance() {
        return Holder.instance;
    }

    public void init( PartnerConfiguration partnerConfiguration ){
        if( this.initialized.compareAndSet(false, true) ){
            int connectionRequestTimeout;
            int connectTimeout;
            int socketTimeout;
            try {
                // request config timeouts (in seconds)
                connectionRequestTimeout = Integer.parseInt(config.get("http.connectionRequestTimeout"));
                connectTimeout = Integer.parseInt(config.get("http.connectTimeout"));
                socketTimeout = Integer.parseInt(config.get("http.socketTimeout"));

                // retries
                this.retries = Integer.parseInt(config.get("http.retries"));
            }
            catch( NumberFormatException e ){
                throw new PluginException("plugin error: http.* properties must be integers", e);
            }

            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(connectionRequestTimeout * 1000)
                    .setConnectTimeout(connectTimeout * 1000)
                    .setSocketTimeout(socketTimeout * 1000)
                    .build();

            try {
                // Build RSA holder from PartnerConfiguration
                if( partnerConfiguration.getProperty( Constants.PartnerConfigurationKeys.CLIENT_CERTIFICATE ) == null ){
                    throw new InvalidDataException("Missing client certificate chain from partner configuration (sentitive properties)");
                }
                if( partnerConfiguration.getProperty( Constants.PartnerConfigurationKeys.CLIENT_PRIVATE_KEY ) == null ){
                    throw new InvalidDataException("Missing client private key from partner configuration (sentitive properties)");
                }

                this.rsaHolder = new RSAHolder.RSAHolderBuilder()
                        .parseChain( partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_CERTIFICATE) )
                        .parsePrivateKey( partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_PRIVATE_KEY) )
                        .build();

            } catch ( IOException | GeneralSecurityException e ){
                throw new PluginException( "A problem occurred initializing SSL context", FailureCause.INVALID_DATA, e );
            }

            // instantiate Apache HTTP client
            this.client = HttpClientBuilder.create()
                    .useSystemProperties()
                    .setDefaultRequestConfig( requestConfig )
                    .setSSLSocketFactory(new SSLConnectionSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), SSLConnectionSocketFactory.getDefaultHostnameVerifier()))
                    .build();
        }
    }
    // --- Singleton Holder pattern + initialization END

    /**
     * Check for an already registered valid authorization.
     * If none exists, request one from the dedicated partner API.
     *
     * @param requestConfiguration the request configuration
     */
    public Authorization authorize( RequestConfiguration requestConfiguration ){
        if( this.isAuthorized() ){
            LOGGER.info("Client already contains a valid authorization");
            return this.authorization;
        }

        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.API_AUTH_BASE_URL);
        if( baseUrl == null ){
            throw new InvalidDataException("Missing auth API base url in partnerConfiguration");
        }

        // Init request
        URI uri;
        try {
            uri = new URI(baseUrl + API_AUTH_PATH_OAUTH_TOKEN);
        }
        catch (URISyntaxException e) {
            throw new InvalidDataException("Authorization API URL is invalid", e);
        }
        HttpPost httpPost = new HttpPost(uri);

        // Authorization header
        ContractProperty clientId = requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.CLIENT_ID);
        ContractProperty clientSecret = requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.CLIENT_SECRET);
        if( clientId == null || clientSecret == null ){
            throw new InvalidDataException("Missing clientId or clientSecret in contractConfiguration");
        }
        String auth = clientId.getValue() + ":" + clientSecret.getValue();
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.ISO_8859_1));
        String authHeader = "Basic " + new String(encodedAuth);
        httpPost.setHeader(HttpHeaders.AUTHORIZATION, authHeader);

        // Content-Type
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");

        // www-form-urlencoded content
        StringEntity data = new StringEntity("grant_type=client_credentials", StandardCharsets.UTF_8);
        httpPost.setEntity(data);

        // Execute request
        StringResponse response = this.execute( httpPost );

        // Handle potential error
        if( !response.isSuccess() ){
            throw this.handleAuthorizationErrorResponse( response );
        }

        // Build authorization from response content
        Authorization.AuthorizationBuilder authBuilder = new Authorization.AuthorizationBuilder();
        try {
            // parse response content
            NatixisAuthorizationResponse authResponse = NatixisAuthorizationResponse.fromJson( response.getContent() );

            // retrieve access token & token type
            if( authResponse.getAccessToken() == null ){
                throw new PluginException("No access_token in " + API_AUTH_PATH_OAUTH_TOKEN + " response", FailureCause.PARTNER_UNKNOWN_ERROR);
            }
            authBuilder.withAccessToken( authResponse.getAccessToken() )
                    .withTokenType( authResponse.getTokenType() == null ? "Bearer" : authResponse.getTokenType() );

            // expiration delay
            long expiresIn = 60L * 5 * 1000; // 5 minutes default expiration time
            if( authResponse.getExpiresIn() != null ){
                expiresIn = 1000L * authResponse.getExpiresIn();
            }

            // authorization time
            long authTime = System.currentTimeMillis();
            if( authResponse.getJwtUserBody() != null ) {
                JwtUserBody jwtUserBody = JwtUserBody.fromJson(authResponse.getJwtUserBody());
                if (jwtUserBody.getAuthTime() != null) {
                    authTime = jwtUserBody.getAuthTime() * 1000;
                }
            }

            // calculate expiration date
            Date expiresAt = new Date( authTime + expiresIn );
            authBuilder.withExpiresAt( expiresAt );

            this.authorization = authBuilder.build();
            return this.authorization;
        }
        catch(JsonSyntaxException | IllegalStateException e){
            throw new PluginException("Failed to parse authorization response", FailureCause.COMMUNICATION_ERROR, e);
        }
    }

    /**
     * Retrieve the list of banks/ASPSPs this partner is linked with.
     *
     * @param requestConfiguration the request configuration
     */
    public NatixisBanksResponse banks(RequestConfiguration requestConfiguration ){
        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.API_PAYMENT_BASE_URL);
        if( baseUrl == null ){
            throw new InvalidDataException( MISSING_PAYMENT_API_URL_ERROR );
        }

        // Execute GET request
        StringResponse response = this.get( baseUrl + API_PAYMENT_PATH_BANK_LIST, requestConfiguration );

        // Handle result (error case has been handled, if needed, by get method)
        try {
            return NatixisBanksResponse.fromJson( response.getContent() );
        }
        catch( RuntimeException e ){
            throw new PluginException("Plugin error: unable to parse bank list", e);
        }
    }

    /**
     * Initialize a payment.
     *
     * @param requestBody the payment initiation request body
     * @param requestConfiguration the request configuration
     */
    public NatixisPaymentInitResponse paymentInit(Payment requestBody, PsuInformation psuInformation, RequestConfiguration requestConfiguration ){
        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.API_PAYMENT_BASE_URL);
        if( baseUrl == null ){
            throw new InvalidDataException( MISSING_PAYMENT_API_URL_ERROR );
        }

        // Authorization
        Authorization auth = this.authorize( requestConfiguration );

        // Init request
        URI uri;
        try {
            uri = new URI( baseUrl + API_PAYMENT_PATH_INIT );
        }
        catch (URISyntaxException e) {
            throw new InvalidDataException("Payment API URL is invalid", e);
        }
        HttpPost httpPost = new HttpPost( uri );

        // Body
        String jsonBody = requestBody.toString();
        httpPost.setEntity( new StringEntity( jsonBody, StandardCharsets.UTF_8 ));

        // Headers
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, auth.getHeaderValue());
        headers.put(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.put(HTTP_HEADER_X_REQUEST_ID, UUID.randomUUID().toString());

        // Digest header
        headers.put("Digest", this.sha256DigestHeader( jsonBody ));

        // PSU information headers
        headers.putAll( this.psuHeaders(psuInformation) );

        // Add headers to the request
        for (Map.Entry<String, String> h : headers.entrySet()) {
            httpPost.setHeader(h.getKey(), h.getValue());
        }

        // Signature
        String keyId = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.SIGNATURE_KEYID);
        Signature signature = this.generateSignature( httpPost, headers, keyId, "(request-target)", HTTP_HEADER_X_REQUEST_ID, "Digest" );
        httpPost.setHeader("Signature", signature.toString());

        // Execute request
        StringResponse response = this.execute( httpPost );

        // Handle potential error
        if( !response.isSuccess() ){
            throw this.handleErrorResponse( response );
        }

        return NatixisPaymentInitResponse.fromStringResponse( response );
    }

    /**
     * Retrieve the status of an ongoing payment request.
     *
     * @param paymentId payment unique identifier
     * @param requestConfiguration the request configuration
     * @return The payment request data
     */
    public Payment paymentStatus( String paymentId, RequestConfiguration requestConfiguration ){
        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.API_PAYMENT_BASE_URL);
        if( baseUrl == null ){
            throw new InvalidDataException( MISSING_PAYMENT_API_URL_ERROR );
        }
        String fullUrl = baseUrl + API_PAYMENT_PATH_STATUS.replace(API_PAYMENT_PATH_STATUS_TOKEN, paymentId);

        // Execute GET request
        StringResponse response = this.get( fullUrl, requestConfiguration );

        // Handle status (error case has been handled by get method)
        try {
            JsonObject paymentRequest = new JsonParser().parse(response.getContent()).getAsJsonObject()
                    .getAsJsonObject("paymentRequest");
            return Payment.fromJson( paymentRequest.toString() );
        }
        catch( RuntimeException e ){
            throw new PluginException("Plugin error: unable to parse status response", e);
        }
    }

    /**
     * Check if there is a current valid authorization.
     * @return `true` if the current authorization is valid, `false` otherwise.
     */
    boolean isAuthorized(){
        return this.authorization != null
                && this.authorization.getExpiresAt().compareTo( new Date() ) > 0;
        /* Warning: the token can be valid at the time this method is called but not anymore 1 second later...
        Maybe add some time (5 minutes ?) to the current time, to be sure. */
    }

    /**
     * Build and execute a GET request to the given URL.
     *
     * @param url the URL to call
     * @param requestConfiguration the request configuration
     * @return the StringResponse obtained.
     */
    StringResponse get( String url, RequestConfiguration requestConfiguration ){
        // Authorization
        Authorization auth = this.authorize( requestConfiguration );

        // Init request
        URI uri;
        try {
            uri = new URI( url );
        }
        catch (URISyntaxException e) {
            throw new InvalidDataException("Service URL is invalid", e);
        }
        HttpGet httpGet = new HttpGet( uri );

        // Headers
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, auth.getHeaderValue());
        headers.put(HTTP_HEADER_X_REQUEST_ID, UUID.randomUUID().toString());
        for (Map.Entry<String, String> h : headers.entrySet()) {
            httpGet.setHeader(h.getKey(), h.getValue());
        }

        // Signature
        String keyId = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.SIGNATURE_KEYID);
        Signature signature = this.generateSignature( httpGet, headers, keyId, "(request-target)", HTTP_HEADER_X_REQUEST_ID );
        httpGet.setHeader("Signature", signature.toString() );

        // Execute request
        StringResponse response = this.execute( httpGet );

        // Handle potential error
        if( !response.isSuccess() ){
            throw this.handleErrorResponse( response );
        }

        return response;
    }

    /**
     * Send the request, with a retry system in case the client does not obtain a proper response from the server.
     *
     * @param httpRequest The request to send.
     * @return The response converted as a {@link StringResponse}.
     * @throws PluginException If an error repeatedly occurs and no proper response is obtained.
     */
    StringResponse execute( HttpRequestBase httpRequest ){
        StringResponse strResponse = null;
        int attempts = 1;

        while( strResponse == null && attempts <= this.retries ){
            if( LOGGER.isDebugEnabled() ){
                LOGGER.debug( "Start call to partner API (attempt {}) :{}", attempts, System.lineSeparator() + PluginUtils.requestToString( httpRequest ) );
            } else {
                LOGGER.info( "Start call to partner API [{} {}] (attempt {})", httpRequest.getMethod(), httpRequest.getURI(), attempts );
            }
            try( CloseableHttpResponse httpResponse = this.client.execute( httpRequest ) ){
                strResponse = StringResponse.fromHttpResponse( httpResponse );
            }
            catch (IOException e) {
                LOGGER.error("An error occurred during the HTTP call :", e);
                strResponse = null;
            }
            finally {
                attempts++;
            }
        }

        if( strResponse == null ){
            throw new PluginException( "Failed to contact the partner API", FailureCause.COMMUNICATION_ERROR );
        }
        LOGGER.info("Response obtained from partner API [{} {}]", strResponse.getStatusCode(), strResponse.getStatusMessage() );
        return strResponse;
    }

    /**
     * Generate a signature for the given request, using the private key returned by the {@link RSAHolder} instance.
     *
     * @param httpRequest The request to sign
     * @param requestHeaders The headers of the request to sign
     * @param keyId The key id to use in the signature process
     * @param signedHeaders The headers that should be used in the signature
     * @return The signature, as a string
     */
    Signature generateSignature( HttpRequestBase httpRequest, Map<String, String> requestHeaders, String keyId, String... signedHeaders ){
        // Retrieve private key
        Key privateKey;
        try {
            privateKey = this.rsaHolder.getPrivateKey();
        } catch (GeneralSecurityException e) {
            throw new PluginException("Plugin error: while recovering private key", e);
        }

        // Generate signature
        Signature signature;
        try {
            signature = new Signature(keyId, SIGNATURE_ALGORITHM, null, signedHeaders );
            Signer signer = new Signer(privateKey, signature);
            signature = signer.sign( httpRequest.getMethod(), httpRequest.getURI().getPath(), requestHeaders );
        }
        catch (IOException | RuntimeException e) {
            throw new PluginException("Plugin error: while signing the request", e);
        }

        return signature;
    }

    /**
     * Handle error responses with RFC 6749 format.
     *
     * @param response The response received, converted as {@link StringResponse}.
     * @return The {@link PluginException} to throw
     */
    PluginException handleAuthorizationErrorResponse( StringResponse response ){
        RFC6749AccessTokenErrorResponse errorResponse;
        try {
            errorResponse = RFC6749AccessTokenErrorResponse.fromJson( response.getContent() );
        }
        catch( JsonSyntaxException e ){
            errorResponse = null;
        }

        if( errorResponse != null && errorResponse.getError() != null ){
            if( errorResponse.getErrorDescription() != null ){
                LOGGER.error( "Authorization error: {}", errorResponse.getErrorDescription() );
            }
            return new PluginException("authorization error: " + errorResponse.getError(), FailureCause.INVALID_DATA);
        }
        else {
            return new PluginException("unknown authorization error", FailureCause.PARTNER_UNKNOWN_ERROR);
        }
    }

    /**
     * Handle error responses with the specified format (see API swagger descriptors).
     *
     * @param response The response received, converted as {@link StringResponse}.
     * @return The {@link PluginException} to throw
     */
    PluginException handleErrorResponse( StringResponse response ){
        // Trying to parse the error response with the specified format
        NatixisErrorResponse errorResponse;
        try {
            errorResponse = NatixisErrorResponse.fromJson( response.getContent() );
        }
        catch( JsonSyntaxException e ){
            errorResponse = null;
        }

        // Extract error code and message from the error response
        String message = "partner error: " + response.getStatusCode() + " " + response.getStatusMessage();
        int errorCode = response.getStatusCode();
        if( errorResponse != null && errorResponse.getMessage() != null ){
            message = errorResponse.getMessage();
            try {
                errorCode = Integer.parseInt(errorResponse.getStatus());
            }
            catch( NumberFormatException e ){
                LOGGER.error("Unable to parse the response status as an integer: {} => using HTTP status code instead: {}",
                        errorResponse.getStatus(), errorCode);
            }
        }

        // Mapping between partner error codes and Payline failure causes
        FailureCause failureCause = FailureCause.PARTNER_UNKNOWN_ERROR;
        if( errorCode >= 400 && errorCode < 500 ){
            failureCause = FailureCause.INVALID_DATA;
        }

        return new PluginException(message, failureCause);
    }

    /**
     * Build PSU headers from a {@link PsuInformation} instance.
     *
     * @param psuInformation The object containing PSU information
     * @return The PSU-related headers
     */
    Map<String, String> psuHeaders( PsuInformation psuInformation ){
        Map<String, String> psuHeaders = new HashMap<>();

        PluginUtils.safePut(psuHeaders, "PSUAddress", psuInformation.getIpAddress() );
        if( psuInformation.getIpPort() != null ){
            psuHeaders.put("PSUPort", psuInformation.getIpPort().toString() );
        }
        PluginUtils.safePut(psuHeaders, "PSUHTTPMethod", psuInformation.getHttpMethod() );
        if( psuInformation.getLastLogin() != null ){
            psuHeaders.put("PSUDate", new SimpleDateFormat(PSUDATE_HEADER_FORMAT).format( psuInformation.getLastLogin() ));
        }
        PluginUtils.safePut(psuHeaders, "PSUUserAgent", psuInformation.getHeaderUserAgent() );
        PluginUtils.safePut(psuHeaders, "PSUReferer", psuInformation.getHeaderReferer() );
        PluginUtils.safePut(psuHeaders, "PSUAccept", psuInformation.getHeaderAccept() );
        PluginUtils.safePut(psuHeaders, "PSUAcceptCharset", psuInformation.getHeaderAcceptCharset() );
        PluginUtils.safePut(psuHeaders, "PSUAcceptEncoding", psuInformation.getHeaderAcceptEncoding() );
        PluginUtils.safePut(psuHeaders, "PSUAcceptLanguage", psuInformation.getHeaderAcceptLanguage() );
        PluginUtils.safePut(psuHeaders, "PsuGeoLocation", psuInformation.getGeoLocation() );
        PluginUtils.safePut(psuHeaders, "PSU-Device-ID", psuInformation.getDeviceId() );

        return psuHeaders;
    }

    /**
     * Remove every space or line break into the given JSON content.
     * Then digest it using SHA-256 algorithm and return the corresponding HTTP header (SHA-256=digestMessage).
     *
     * @param jsonContent the JSON content to digest
     * @return the digest header
     */
    String sha256DigestHeader( String jsonContent ){
        String digestBody = PluginUtils.jsonMinify( jsonContent );
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest( digestBody.getBytes() );
        } catch (NoSuchAlgorithmException e) {
            // This should not happen, as "SHA-256" has been tested as a valid algorithm
            throw new PluginException("Plugin error: no such digest algorithm", e);
        }
        return "SHA-256=" + new String(Base64.encodeBase64(digest));
    }



}
