package org.wso2.support.sample.secret.handler;

import java.io.File;
import org.wso2.carbon.utils.CarbonUtils;

/**
 * Constants used in the custom secret handler implementation.
 *
 * <p>All property keys listed here can be set in the configuration file at {@link
 * #PROPERTIES_FILE_PATH}.
 */
public final class Constants {

  // Evaluates to: <IS_HOME>/repository/conf/security/custom-secret-handler.properties
  public static final String PROPERTIES_FILE_PATH =
      CarbonUtils.getCarbonConfigDirPath()
          + File.separator
          + "security"
          + File.separator
          + "custom-secret-handler.properties";

  // ── Property Keys ──────────────────────────────────────────────────────────

  /** The URL of the remote secrets API endpoint. */
  public static final String PROP_API_URL = "api.url";

  /** HTTP connection timeout in milliseconds. */
  public static final String PROP_CONNECT_TIMEOUT = "api.timeout.connect";

  /** HTTP socket (read) timeout in milliseconds. */
  public static final String PROP_SOCKET_TIMEOUT = "api.timeout.socket";

  /** Maximum total connections in the HTTP connection pool. */
  public static final String PROP_MAX_TOTAL_CONN = "api.pool.max.total";

  /** Maximum connections per route in the HTTP connection pool. */
  public static final String PROP_MAX_PER_ROUTE = "api.pool.max.per.route";

  /** Set to {@code true} to disable SSL hostname verification (NOT recommended for production). */
  public static final String PROP_DISABLE_HOSTNAME_VERIFICATION =
      "api.ssl.hostname.verifier.disable";

  /**
   * Prefix for custom HTTP headers sent with every request. Example: {@code
   * api.header.Authorization = Bearer my-token}
   */
  public static final String PROP_CUSTOM_HEADERS_PREFIX = "api.header.";

  /**
   * The JSON key in the API response that contains the secret value. Defaults to {@value
   * #DEFAULT_RESPONSE_SECRET_KEY}.
   */
  public static final String PROP_RESPONSE_SECRET_KEY = "api.response.secret.key";

  // ── Default Values ─────────────────────────────────────────────────────────

  public static final String DEFAULT_API_URL = "http://localhost:5001/secrets";
  public static final int DEFAULT_CONNECT_TIMEOUT = 5000;
  public static final int DEFAULT_SOCKET_TIMEOUT = 5000;
  public static final int DEFAULT_MAX_TOTAL_CONN = 20;
  public static final int DEFAULT_MAX_PER_ROUTE = 5;
  public static final boolean DEFAULT_DISABLE_HOSTNAME_VERIFICATION = false;
  public static final String DEFAULT_RESPONSE_SECRET_KEY = "secret";

  // ── JSON Keys ──────────────────────────────────────────────────────────────

  /** The JSON key in the request body that carries the alias. */
  public static final String JSON_KEY_ALIAS = "alias";

  // ── HTTP Headers & Media Types ─────────────────────────────────────────────

  public static final String HEADER_CONTENT_TYPE = "Content-Type";
  public static final String HEADER_ACCEPT = "Accept";
  public static final String MEDIA_TYPE_APPLICATION_JSON = "application/json";

  private Constants() {
    // Prevent instantiation
  }
}
