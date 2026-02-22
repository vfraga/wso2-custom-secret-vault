package org.wso2.support.sample.secret.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

/**
 * Singleton helper that communicates with an external REST API to retrieve secrets.
 *
 * <p>Configuration is read from a properties file located at {@code
 * <IS_HOME>/repository/conf/security/custom-secret-handler.properties}. If the file does not exist,
 * sensible defaults are used.
 *
 * <p>Retrieved secrets are cached in-memory for the lifetime of the server so that the remote API
 * is called at most once per alias.
 */
public final class APIClientHelper {

  private static final Log log = LogFactory.getLog(APIClientHelper.class);
  private static final APIClientHelper instance = new APIClientHelper();

  private final CloseableHttpClient httpClient;
  private final Map<String, String> secretCache;
  private final Properties properties;

  private APIClientHelper() {
    this.properties = loadProperties();
    this.secretCache = new ConcurrentHashMap<>();
    this.httpClient = createHttpClient();
  }

  public static APIClientHelper getInstance() {
    return instance;
  }

  /**
   * Loads configuration from the properties file. If the file does not exist or cannot be read, an
   * empty {@link Properties} is returned so that all properties fall back to their defaults.
   */
  private Properties loadProperties() {
    final Properties props = new Properties();
    final Path path = Paths.get(Constants.PROPERTIES_FILE_PATH);

    if (!Files.exists(path)) {
      log.warn(
          "Configuration file not found at: "
              + path
              + ". Using default settings. Create this file to customise the secret handler.");
      return props;
    }

    try (final InputStream input = Files.newInputStream(path)) {
      props.load(input);
    } catch (IOException ex) {
      log.error("Error loading properties file at: " + path + ". Using default settings.", ex);
    }

    return props;
  }

  private CloseableHttpClient createHttpClient() {
    final int maxTotal =
        Integer.parseInt(
            properties.getProperty(
                Constants.PROP_MAX_TOTAL_CONN, String.valueOf(Constants.DEFAULT_MAX_TOTAL_CONN)));
    final int defaultMaxPerRoute =
        Integer.parseInt(
            properties.getProperty(
                Constants.PROP_MAX_PER_ROUTE, String.valueOf(Constants.DEFAULT_MAX_PER_ROUTE)));
    final int connectTimeout =
        Integer.parseInt(
            properties.getProperty(
                Constants.PROP_CONNECT_TIMEOUT, String.valueOf(Constants.DEFAULT_CONNECT_TIMEOUT)));
    final int socketTimeout =
        Integer.parseInt(
            properties.getProperty(
                Constants.PROP_SOCKET_TIMEOUT, String.valueOf(Constants.DEFAULT_SOCKET_TIMEOUT)));
    final boolean disableHostnameVerification =
        Boolean.parseBoolean(
            properties.getProperty(
                Constants.PROP_DISABLE_HOSTNAME_VERIFICATION,
                String.valueOf(Constants.DEFAULT_DISABLE_HOSTNAME_VERIFICATION)));

    final PoolingHttpClientConnectionManager poolingConnManager =
        new PoolingHttpClientConnectionManager();

    poolingConnManager.setMaxTotal(maxTotal);
    poolingConnManager.setDefaultMaxPerRoute(defaultMaxPerRoute);

    final RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectTimeout(connectTimeout)
            .setSocketTimeout(socketTimeout)
            .setCookieSpec(CookieSpecs.STANDARD)
            .build();

    final SSLConnectionSocketFactory sslSocketFactory;
    if (disableHostnameVerification) {
      log.warn(
          "Hostname verification is DISABLED. This is not recommended for production environments.");
      sslSocketFactory =
          new SSLConnectionSocketFactory(
              SSLContexts.createDefault(), NoopHostnameVerifier.INSTANCE);
    } else {
      sslSocketFactory = SSLConnectionSocketFactory.getSocketFactory();
    }

    return HttpClients.custom()
        .setConnectionManager(poolingConnManager)
        .setDefaultRequestConfig(requestConfig)
        .setSSLSocketFactory(sslSocketFactory)
        .build();
  }

  /**
   * Retrieves the secret for the given alias. Results are cached in-memory.
   *
   * @param alias The key alias.
   * @return The plaintext secret value, or {@code null} if not found or an error occurs.
   */
  public String getSecret(final String alias) {
    if (alias == null || alias.isEmpty()) {
      log.warn("Alias is null or empty");
      return null;
    }

    final String cached = secretCache.get(alias);
    if (cached != null) {
      if (log.isDebugEnabled()) {
        log.debug("Cache hit for alias: " + alias);
      }
      return cached;
    }

    final String secret = fetchSecretFromAPI(alias);
    if (secret != null && !secret.isEmpty()) {
      secretCache.put(alias, secret);
      return secret;
    }
    return null;
  }

  /**
   * Calls the remote REST API to fetch a secret by alias.
   *
   * <p>The API contract is:
   *
   * <pre>
   *   POST {api.url}
   *   Content-Type: application/json
   *
   *   { "alias": "<alias>" }
   * </pre>
   *
   * Expected response on success (HTTP 200):
   *
   * <pre>
   *   { "secret": "<plaintext_value>" }
   * </pre>
   *
   * The response JSON key name is configurable via {@link Constants#PROP_RESPONSE_SECRET_KEY}.
   *
   * <p>Custom HTTP headers (e.g., for authentication tokens) can be configured in the properties
   * file. See {@link Constants#PROP_CUSTOM_HEADERS_PREFIX}.
   */
  private String fetchSecretFromAPI(final String alias) {
    final String apiUrl = properties.getProperty(Constants.PROP_API_URL, Constants.DEFAULT_API_URL);
    final String responseSecretKey =
        properties.getProperty(
            Constants.PROP_RESPONSE_SECRET_KEY, Constants.DEFAULT_RESPONSE_SECRET_KEY);

    if (log.isDebugEnabled()) {
      log.debug("Fetching secret for alias '" + alias + "' from API: " + apiUrl);
    }

    final HttpPost request = new HttpPost(apiUrl);

    // Build the JSON request body.
    final JSONObject json = new JSONObject();
    json.put(Constants.JSON_KEY_ALIAS, alias);
    final String jsonBody = json.toJSONString();

    try {
      final StringEntity entity = new StringEntity(jsonBody);
      request.setEntity(entity);
      request.setHeader(Constants.HEADER_CONTENT_TYPE, Constants.MEDIA_TYPE_APPLICATION_JSON);
      request.setHeader(Constants.HEADER_ACCEPT, Constants.MEDIA_TYPE_APPLICATION_JSON);

      // Apply any custom headers (e.g., api.header.Authorization = Bearer <token>).
      for (String key : properties.stringPropertyNames()) {
        if (key.startsWith(Constants.PROP_CUSTOM_HEADERS_PREFIX)) {
          final String headerName = key.substring(Constants.PROP_CUSTOM_HEADERS_PREFIX.length());
          request.setHeader(headerName, properties.getProperty(key));
        }
      }

      try (final CloseableHttpResponse response = httpClient.execute(request)) {
        final int statusCode = response.getStatusLine().getStatusCode();
        final HttpEntity responseEntity = response.getEntity();
        final String responseBody =
            responseEntity != null ? EntityUtils.toString(responseEntity) : null;

        if (statusCode == 200 && responseBody != null) {
          return extractSecret(responseBody, responseSecretKey);
        } else {
          log.error(
              "Failed to retrieve secret for alias '"
                  + alias
                  + "'. HTTP Status: "
                  + statusCode
                  + (responseBody != null ? ". Response: " + responseBody : ""));
        }
      }
    } catch (IOException e) {
      log.error("Error connecting to secret API for alias: " + alias, e);
    }
    return null;
  }

  /**
   * Extracts the secret value from the API response body.
   *
   * <p>Attempts to parse the response as JSON and read the configured key. Falls back to treating
   * the entire response body as the raw secret value (for APIs that return plain text).
   */
  private String extractSecret(final String responseBody, final String secretKey) {
    try {
      final Object parsed = JSONValue.parse(responseBody);
      if (parsed instanceof JSONObject) {
        final Object value = ((JSONObject) parsed).get(secretKey);
        if (value != null) {
          return value.toString();
        }
      }
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("Response is not valid JSON, treating as raw secret value.");
      }
    }
    // Fallback: treat the entire body as the raw secret.
    return responseBody.trim();
  }
}
