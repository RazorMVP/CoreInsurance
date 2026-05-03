package com.nubeero.cia.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.regex.Pattern;

/**
 * Fail-fast validator for the NDPR PII encryption key
 * (<code>cia.security.pii-key</code> / <code>PII_ENCRYPTION_KEY</code>).
 *
 * <p>The key value is interpolated into Hikari's
 * <code>connection-init-sql: "SET app.pii_key = '${cia.security.pii-key}'"</code>
 * on every pooled connection. Without validation, a key containing a single
 * quote, semicolon, or newline would break out of the SQL literal and could
 * inject arbitrary SQL onto every connection in the pool.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} — after placeholder
 * resolution but <strong>before</strong> the DataSource bean is created.
 * Registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.
 */
public class PiiKeyValidator implements EnvironmentPostProcessor {

    private static final String PROPERTY = "cia.security.pii-key";

    /** Allowed character set: alphanumerics plus base64/url-safe symbols. */
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9+/=._\\-]{12,256}$");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String key = environment.getProperty(PROPERTY);

        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "Required property '" + PROPERTY + "' is not set. " +
                "Set environment variable PII_ENCRYPTION_KEY before starting the application. " +
                "Recommended: 32+ random bytes, base64-encoded. " +
                "Without this key, customer PII cannot be encrypted at rest (NDPR violation)."
            );
        }

        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalStateException(
                "Property '" + PROPERTY + "' is invalid. " +
                "Allowed characters: A-Z a-z 0-9 + / = . _ - (length 12-256). " +
                "This restriction prevents SQL injection via the Hikari " +
                "connection-init-sql clause `SET app.pii_key = '<key>'` and " +
                "ensures the key survives shell/YAML escaping unchanged."
            );
        }
    }
}
