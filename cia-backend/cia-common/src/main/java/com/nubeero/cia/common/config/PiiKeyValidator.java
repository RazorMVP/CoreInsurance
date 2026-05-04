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
 *
 * <h2>Pre-flight runbook (operator checklist)</h2>
 * Before promoting a tenant or environment to production:
 * <ol>
 *   <li><b>Generate a key.</b> Use {@code openssl rand -base64 32} (or a
 *       secret-manager equivalent). 32 random bytes encoded as base64 yields
 *       a 44-character string that satisfies the validator regex.</li>
 *   <li><b>Store the key in a secret manager,</b> not in source-controlled
 *       config files. AWS Secrets Manager, GCP Secret Manager, HashiCorp
 *       Vault, or equivalent. Reference it from deployment manifests as
 *       {@code PII_ENCRYPTION_KEY}.</li>
 *   <li><b>Verify the key is set</b> in the running environment before
 *       deploying: {@code echo "${PII_ENCRYPTION_KEY:-MISSING}"} should
 *       print the key (or its length), not "MISSING". A missing key fails
 *       startup loudly, which is the design — but catching it pre-deploy
 *       avoids a false-fire incident.</li>
 *   <li><b>Back up the key</b> in a separate vault location accessible to
 *       at least two on-call engineers. Loss of the key is unrecoverable —
 *       all encrypted PII (id_number, id_document_url, address) becomes
 *       unreadable. There is no master backdoor.</li>
 *   <li><b>Verify Flyway can read the key.</b> V24 encrypts existing rows
 *       in place using {@code current_setting('app.pii_key')}. Both the
 *       application and the migration share the Hikari pool, so if startup
 *       passes this validator, V24 will see the same key.</li>
 *   <li><b>Rotation:</b> there is no automated rotation path. To rotate,
 *       schedule a maintenance window: decrypt with old key, re-encrypt
 *       with new key, swap the secret. Plan for table-rewrite duration
 *       (see V24 perf note in the migration file).</li>
 * </ol>
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
