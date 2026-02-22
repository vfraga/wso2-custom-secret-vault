package org.wso2.support.sample.secret.handler;

import org.wso2.securevault.secret.AbstractSecretCallbackHandler;
import org.wso2.securevault.secret.SingleSecretCallback;

/**
 * A {@link org.wso2.securevault.secret.SecretCallbackHandler} that resolves secret aliases by
 * calling an external REST API (e.g., CyberArk, HashiCorp Vault, Azure Key Vault, or any custom key
 * management service).
 *
 * <p>This class extends {@link AbstractSecretCallbackHandler} from the {@code org.wso2.securevault}
 * library, which is the standard extension point for providing custom secret retrieval logic in
 * WSO2 products.
 *
 * <p>When the WSO2 Carbon runtime needs to resolve a secret alias (e.g., a database password, LDAP
 * bind password, or any other sensitive value protected by the secure vault), it invokes this
 * handler's {@link #handleSingleSecretCallback} method. The handler delegates the actual API
 * communication to {@link APIClientHelper}.
 *
 * <p>To use this handler, configure its fully qualified class name as the {@code
 * carbon.secretProvider} in {@code <IS_HOME>/repository/conf/security/secret-conf.properties}:
 *
 * <pre>
 *   carbon.secretProvider="org.wso2.support.sample.secret.handler.APIBasedSecretHandler"
 * </pre>
 */
public class APIBasedSecretHandler extends AbstractSecretCallbackHandler {

  @Override
  protected void handleSingleSecretCallback(final SingleSecretCallback singleSecretCallback) {
    final String alias = singleSecretCallback.getId();

    if (alias == null || alias.isEmpty()) {
      if (log.isDebugEnabled()) {
        log.debug("Secret callback has a null or empty alias. Skipping.");
      }
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("Resolving secret for alias: " + alias);
    }

    final String secret = APIClientHelper.getInstance().getSecret(alias);

    if (secret != null && !secret.isEmpty()) {
      singleSecretCallback.setSecret(secret);
    } else {
      log.warn("No secret found for alias: " + alias);
    }
  }
}
