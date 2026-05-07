package com.nubeero.cia.tenant;

import java.util.regex.Pattern;

final class TenantSchemaName {

    static final Pattern SAFE_SCHEMA_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    static final Pattern SAFE_SUBDOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private TenantSchemaName() {
    }

    static boolean isSafeSchemaName(String value) {
        return value != null && SAFE_SCHEMA_NAME.matcher(value).matches();
    }

    static boolean isSafeSubdomain(String value) {
        return value != null && SAFE_SUBDOMAIN.matcher(value).matches();
    }
}
