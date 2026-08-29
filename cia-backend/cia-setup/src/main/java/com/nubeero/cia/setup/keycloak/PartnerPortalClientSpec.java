package com.nubeero.cia.setup.keycloak;

import java.util.List;

/** Client id + redirect URIs for the partner-portal SPA public client upsert. */
public record PartnerPortalClientSpec(String clientId, List<String> redirectUris) {
}
