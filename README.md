# Custom Secret Handler for WSO2 Identity Server

A sample WSO2 Carbon component that replaces the default file-based secure vault with a **REST API-based secret provider**. Instead of reading encrypted passwords from `cipher-text.properties` and decrypting them locally, this handler fetches plaintext secrets from an external key vault service (e.g., CyberArk, HashiCorp Vault, Azure Key Vault, AWS Secrets Manager, or any custom REST API).

> **Note:** This is a vendor-agnostic sample that can be adapted to any secret provider that exposes an HTTP REST API.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [How the WSO2 Secure Vault Works](#how-the-wso2-secure-vault-works)
- [Available Customisation Points](#available-customisation-points)
  - [1. SecretCallbackHandler (This Project)](#1-secretcallbackhandler-this-project)
  - [2. SecretRepository + SecretRepositoryProvider](#2-secretrepository--secretrepositoryprovider)
- [Project Structure](#project-structure)
- [How This Sample Works](#how-this-sample-works)
- [Configuration Reference](#configuration-reference)
- [Building](#building)
- [Deployment TOML configuration](#deployment-toml-configuration)
- [Testing with the Demo Server](#testing-with-the-demo-server)
- [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │                    WSO2 Identity Server                              │
 │                                                                      │
 │  ┌───────────────┐    ┌────────────────────────┐                     │
 │  │  Data Source  │    │    UserStoreManager    │  ... other          │
 │  │ configuration │    │        configs         │  components         │
 │  └──────┬────────┘    └───────────┬────────────┘                     │
 │         │     "resolve alias"     │                                  │
 │         ▼                         ▼                                  │
 │  ┌──────────────────────────────────────────────┐                    │
 │  │       SecretCallbackHandlerService           │ (OSGi service)     │
 │  │       ┌──────────────────────────┐           │                    │
 │  │       │ API-Based Secret Handler │◄── THIS PROJECT                │
 │  │       └────────────┬─────────────┘           │                    │
 │  └────────────────────┼─────────────────────────┘                    │
 │                       │                                              │
 └───────────────────────┼──────────────────────────────────────────────┘
                         │ HTTPS POST {"alias": "..."}
                         ▼
              ┌─────────────────────┐
              │  External Key Vault │
              │  (CyberArk, Vault,  │
              │   Azure KV, etc.)   │
              └─────────────────────┘
```

---

## How the WSO2 Secure Vault Works

Understanding the full secret resolution flow is essential to know which extension point to use:

### 1. Server Startup

When the Carbon kernel starts, the `SecretManagerInitializerComponent` (an OSGi Declarative Services component in [`org.wso2.carbon.securevault`](https://github.com/wso2/carbon-kernel/tree/v4.6.1/core/org.wso2.carbon.securevault)) is activated. It:

1. Reads `<IS_HOME>/repository/conf/security/secret-conf.properties`.
2. Passes those properties to `SecretManager.init(properties)`.
3. `SecretManager` reads `secret-manager.properties`, creates key stores, and initializes a chain of `SecretRepository` instances (the default is `FileBaseSecretRepository`, which reads `cipher-text.properties`).
4. If `SecretManager` initialises successfully, a `SecretManagerSecretCallbackHandler` (backed by the `SecretManager` singleton) is wrapped in a `SecretCallbackHandlerServiceImpl` and registered as an OSGi service.
5. If `SecretManager` fails to initialize (e.g., key stores are not configured), a fallback `SecretCallbackHandler` is loaded from the `carbon.secretProvider` property.

### 2. Secret Resolution at Runtime

When a Carbon component (data source, user store, etc.) needs a secret:

1. It looks up the `SecretCallbackHandlerService` from the OSGi registry.
2. Creates a `[SingleSecretCallback](https://github.com/wso2/carbon-secvault/blob/v1.1.3/src/main/java/org/wso2/securevault/secret/SingleSecretCallback.java)("alias.name")`.
3. Calls `handler.handle(new SecretCallback[]{ callback })`.
4. Reads the resolved secret from `callback.getSecret()`.

### 3. The Default Flow (File-Based)

```
secret-conf.properties
        │
        ▼
SecretManager.init()
        │
        ├── KeyStoreInformationFactory → identity + trust key stores
        ├── FileBaseSecretRepositoryProvider → FileBaseSecretRepository
        │       │
        │       ├── Loads cipher-text.properties (alias → Base64 ciphertext)
        │       └── Decrypts all entries using the private key
        │
        └── SecretManagerSecretCallbackHandler
                │
                └── getSecret(alias) → returns decrypted plaintext
```

### 4. The Custom Flow (This Project)

This project **bypasses the entire key store + cipher-text.properties chain**. Instead:

```
APIBasedSecretHandler.handleSingleSecretCallback(alias)
        │
        ▼
APIClientHelper.getSecret(alias)
        │
        ├── Check in-memory cache → hit? return immediately
        │
        └── POST to remote API → {"alias": "..."} → {"secret": "plaintext"}
                │
                └── Cache and return
```

No local key stores, no `cipher-text.properties`, no local decryption. The external vault is the single source of truth.

---

## Available Customisation Points

The WSO2 secure vault framework offers **two main extension points** for integrating a custom key vault. Each serves a different purpose:

### 1. SecretCallbackHandler (This Project)

|                         |                                                                                                                                                                       |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Interface**           | `org.wso2.securevault.secret.SecretCallbackHandler`                                                                                                                   |
| **Abstract base**       | `org.wso2.securevault.secret.AbstractSecretCallbackHandler`                                                                                                           |
| **Method to implement** | `handleSingleSecretCallback(SingleSecretCallback)`                                                                                                                    |
| **When to use**         | When you want to **completely replace** the secret resolution mechanism — fetching secrets from an external API, a hardware security module, an in-memory cache, etc. |
| **Configuration**       | Set `carbon.secretProvider` in `secret-conf.properties` to your handler's FQCN.                                                                                       |

**This is the extension point used in this project.** It is the simplest and most appropriate approach for REST API-based key vaults, because:

- It intercepts secrets **at the callback level**, before the `SecretManager`/`SecretRepository` chain is even consulted.
- It does not require local key stores or `cipher-text.properties`.
- It can be configured as the `secretProvider` fallback, meaning the standard `SecretManager` initialisation (which requires key stores) can be skipped entirely.

**How the handler is loaded:**

The `SecretManagerInitializer.init()` method (in `org.wso2.carbon.securevault`) has the following logic:

```
1. Try to initialise SecretManager (key stores + repositories)
2. If SecretManager fails to initialise:
     → Load the class named in "carbon.secretProvider" property
     → Use it as the SecretCallbackHandler
3. If SecretManager succeeded:
     → Use SecretManagerSecretCallbackHandler (backed by SecretManager)
```

By **not configuring key stores** and setting `carbon.secretProvider` to our handler class, the framework falls through to step 2 and uses our handler directly.

---

### 2. SecretRepository + SecretRepositoryProvider

|                          |                                                                                                                                                                                                                                                         |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Interfaces**           | `org.wso2.securevault.secret.SecretRepository` and `org.wso2.securevault.secret.SecretRepositoryProvider`                                                                                                                                               |
| **Methods to implement** | `init(Properties, String)`, `getSecret(String)`, `getEncryptedData(String)`, `setParent(SecretRepository)`, `getParent()` (on `SecretRepository`); `getSecretRepository(IdentityKeyStoreWrapper, TrustKeyStoreWrapper)` (on `SecretRepositoryProvider`) |
| **When to use**          | When you want to **replace where encrypted secrets are stored** while keeping the standard `SecretManager` initialisation flow (key stores, cipher decryption).                                                                                         |
| **Configuration**        | Set `secretRepositories.vault.provider` in `secret-manager.properties` to your provider's FQCN.                                                                                                                                                         |

The default implementation is `FileBaseSecretRepository` + `FileBaseSecretRepositoryProvider`. This reads `cipher-text.properties` and decrypts each entry using the identity key store.

You would use this extension point if you want to, for example, store encrypted ciphertexts in a database instead of a file, but still use WSO2's local key stores for decryption.

**For a REST API-based vault, this is NOT the right extension point**, because:
- The [`SecretRepositoryProvider.getSecretRepository()`](https://github.com/wso2/carbon-secvault/blob/v1.1.3/src/main/java/org/wso2/securevault/secret/SecretRepositoryProvider.java) receives `IdentityKeyStoreWrapper` and `TrustKeyStoreWrapper` — infrastructure that is unnecessary when the vault handles all cryptography.
- The `SecretManager` still requires valid key store passwords to initialize, even if the repository never uses them.
- The `SecretRepository` interface has `getEncryptedData()` which conceptually doesn't apply when secrets are fetched as plaintext from a remote API.

---

### Summary Table

| Extension Point           | Complexity | Requires Key Stores? | Best For                                              |
|---------------------------|------------|----------------------|-------------------------------------------------------|
| **SecretCallbackHandler** | Low        | No                   | REST API vaults, HSMs, any external provider          |
| **SecretRepository**      | Medium     | Yes                  | Custom storage for encrypted secrets (DB, LDAP, etc.) |

---

## Project Structure

```
custom-secret-handler/
├── pom.xml                                 Maven build
├── README.md                               This file
├── demo-vault-server.py                    Minimal Flask server mimicking a key vault
├── secrets.json                            Sample secrets for the demo server
├── requirements.txt                        Python dependencies for the demo server
├── sample-config/
│   └── custom-secret-handler.properties    Sample configuration file
└── src/main/java/org/wso2/support/sample/secret/handler/
    ├── APIBasedSecretHandler.java          The SecretCallbackHandler implementation
    ├── APIClientHelper.java                HTTP client singleton (pooled, cached)
    └── Constants.java                      Configuration keys and defaults
```

---

## How This Sample Works

### `APIBasedSecretHandler`

Extends `AbstractSecretCallbackHandler`. When the Carbon runtime asks for a secret:

1. Receives a `SingleSecretCallback` with the alias (e.g., `"admin.password"`).
2. Delegates to `APIClientHelper.getInstance().getSecret(alias)`.
3. Sets the returned plaintext on the callback.

### `APIClientHelper`

A singleton that manages HTTP communication with the remote vault API:

- **Configuration:** Reads `custom-secret-handler.properties` from the `repository/conf/security` directory. If the file doesn't exist, uses defaults.
- **Connection pooling:** Uses Apache `PoolingHttpClientConnectionManager` with configurable pool sizes.
- **Caching:** Maintains a `ConcurrentHashMap<alias, secret>` so each alias is fetched at most once.
- **Request format:** `POST` with JSON body `{"alias": "<name>"}`.
- **Response parsing:** Expects JSON `{"secret": "<value>"}`. The JSON key is configurable. Falls back to treating the raw response body as the secret.
- **Custom headers:** Any property starting with `api.header.*` is sent as an HTTP header (useful for Bearer tokens, API keys, etc.).
- **SSL:** Hostname verification can be disabled for development environments.

---

## Configuration Reference

All properties are set in:
```
<IS_HOME>/repository/conf/security/custom-secret-handler.properties
```

A sample file is provided in `sample-config/custom-secret-handler.properties`.

| Property                            | Default                         | Description                                                                                   |
|-------------------------------------|---------------------------------|-----------------------------------------------------------------------------------------------|
| `api.url`                           | `http://localhost:5001/secrets` | The URL of the remote secrets API endpoint.                                                   |
| `api.response.secret.key`           | `secret`                        | JSON key in the API response containing the plaintext secret. For CyberArk, set to `Content`. |
| `api.header.<Name>`                 | *(none)*                        | Custom HTTP headers. Example: `api.header.Authorization = Bearer token123`                    |
| `api.timeout.connect`               | `5000`                          | HTTP connection timeout (ms).                                                                 |
| `api.timeout.socket`                | `5000`                          | HTTP socket/read timeout (ms).                                                                |
| `api.pool.max.total`                | `20`                            | Maximum total connections in the pool.                                                        |
| `api.pool.max.per.route`            | `5`                             | Maximum connections per route.                                                                |
| `api.ssl.hostname.verifier.disable` | `false`                         | Disable SSL hostname verification. **Not for production.**                                    |

### API Contract

**Request:**
```http
POST <api.url>
Content-Type: application/json

{"alias": "<alias_name>"}
```

**Expected Response (HTTP 200):**
```json
{"secret": "<plaintext_value>"}
```

The key name (`secret`) is configurable via `api.response.secret.key`. If the response is not valid JSON, the entire response body is treated as the raw secret value.

---

## Building

```bash
mvn clean package
```

The output bundle JAR is at `target/custom-secret-handler-1.0-SNAPSHOT.jar`.

**Requirements:** Java 8, Maven 3.6+.

---

## Deployment TOML configuration

This is the approach validated on WSO2 IS 5.11.0 where configuration is managed via `deployment.toml`.

### Step 1: Copy the JAR

```bash
cp target/custom-secret-handler-1.0-SNAPSHOT.jar \
   <IS_HOME>/repository/components/lib/
```

### Step 2: Copy the configuration file

```bash
cp sample-config/custom-secret-handler.properties \
   <IS_HOME>/repository/conf/security/
```

Edit the file to set `api.url` and any authentication headers for your vault.

### Step 3: Configure `deployment.toml`

Add the following to `<IS_HOME>/repository/conf/deployment.toml`:

```toml
[secrets]
admin_password = ""
keystore_password = ""
# Add all aliases your vault should resolve. Remember, the value itself isn't used.
```

The `[secrets]` section declares which aliases are "protected tokens" — the property keys are the alias names that will be passed to the handler.

### Step 4: Configure `secret-conf.properties`

Add the following to `<IS_HOME>/repository/conf/security/secret-conf.properties`:

```properties
carbon.secretProvider=org.wso2.support.sample.secret.handler.APIBasedSecretHandler
```

The `secretProvider` tells the Carbon kernel to use this handler class when the `SecretManager` does not fully initialize (which is the expected path when you don't need local key stores).

### Step 5: Restart the server

```bash
<IS_HOME>/bin/wso2server.sh
```

---

## Testing with the Demo Server

A minimal Flask server (`demo-server.py`) is included to simulate a key vault for local development.

### Setup

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Start the server

```bash
python demo-vault-server.py
```

This starts a server on `http://0.0.0.0:5001` with sample secrets from `secrets.json`:

```json
{
    "DB_PASSWORD": "super_secret_password",
    "API_KEY": "12345-abcde-67890",
    "foo": "foo_val",
    "bar": "bar_val"
}
```

### Test manually

```bash
curl -X POST http://localhost:5001/secrets \
  -H "Content-Type: application/json" \
  -d '{"alias": "DB_PASSWORD"}'
```

Expected response:
```json
{"secret": "super_secret_password"}
```

---

## Adapting for External Vaults

To use this with external vaults (e.g., HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, CyberArk CCP), you will likely need to:

1. Set `api.url` to your vault endpoint:
   ```properties
   api.url = https://example.com/VaultService/api
   ```

2. Set the response key to `Content`, or whatever is the field for the secret value in your API response:
   ```properties
   api.response.secret.key = Content
   ```

3. If using client certificate authentication, configure the JVM's trust store and key store via system properties, or customise `APIClientHelper.createHttpClient()` to load custom SSL contexts.

4. If the external vault requires query parameters instead of a JSON body, you would need to modify `APIClientHelper.fetchSecretFromAPI()` to use `HttpGet` with URL-encoded parameters instead of `HttpPost` with a JSON body.

---

## Troubleshooting

### "Configuration file not found" warning at startup

This is expected if you haven't created the properties file. The handler will use default settings. Create the file at:
```
<IS_HOME>/repository/conf/security/custom-secret-handler.properties
```

### Secrets resolve to the alias name itself

This means the handler is not being used. Check:
1. The JAR is in `<IS_HOME>/repository/components/lib/`.
2. `deployment.toml` has the correct `secretProvider` class name.
3. The server was restarted after configuration changes.

### "Error connecting to secret API"

Check:
1. The vault API is reachable from the IS server.
2. The URL in `api.url` is correct.
3. Firewall/network rules allow the connection.
4. If using HTTPS, the vault's certificate is trusted by the JVM's trust store.

### Logging & Debugging

To enable debug logs for the authenticator, modify the `log4j2.properties` file found in `<IS_HOME>/repository/conf`.

1. Append `custom_secret_handler` to the list of loggers:
    ```properties
    loggers = AUDIT_LOG, ..., custom_secret_handler
    ```
2. Add the logger configuration:
    ```properties
    logger.custom_secret_handler.name =org.wso2.support.sample.secret.handler
    logger.custom_secret_handler.level = DEBUG
    ```

---

## License

This sample is provided as-is for demonstration purposes. Licensed under the Apache License Version 2.0.

