package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.setup.company.dto.PasswordPolicyRequest;
import com.nubeero.cia.setup.company.dto.PasswordPolicyResponse;
import com.nubeero.cia.setup.keycloak.KeycloakPasswordPolicySyncer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-side password-policy bookkeeping. Mirrors the V3 password_policies
 * DEFAULTs when no row exists so the UI never has to model an empty state.
 *
 * <p>Actual login-time enforcement is owned by Keycloak's realm password
 * policy — this service stores the tenant-facing intent only. Wiring this
 * config back into the Keycloak realm is a separate slice (F4-sync) and
 * deliberately out of scope here; see backlog row.
 */
@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    /** Mirror the V3 DDL DEFAULTs so first-GET-before-PUT returns a usable shape. */
    static final int     DEFAULT_MIN_LENGTH         = 8;
    static final int     DEFAULT_MAX_LENGTH         = 128;
    static final boolean DEFAULT_REQUIRE_UPPERCASE  = true;
    static final boolean DEFAULT_REQUIRE_LOWERCASE  = true;
    static final boolean DEFAULT_REQUIRE_NUMBERS    = true;
    static final boolean DEFAULT_REQUIRE_SPECIAL    = false;
    static final int     DEFAULT_EXPIRY_DAYS        = 90;
    static final int     DEFAULT_MAX_FAILED         = 5;

    private final PasswordPolicyRepository                      repository;
    private final AuditService                                  auditService;
    /**
     * F4-sync delegate. {@link ObjectProvider} so that when the underlying
     * bean is absent (test / dev-without-Keycloak), the field value is an
     * empty provider rather than a missing-bean injection failure. Type is
     * intentionally the syncer class — not any Keycloak admin-client type —
     * to keep Keycloak symbols out of {@code PasswordPolicyService}'s
     * bytecode (parallel to the F1e-sync encapsulation in {@code UserService}).
     */
    private final ObjectProvider<KeycloakPasswordPolicySyncer>  policySyncer;

    @Transactional(readOnly = true)
    public PasswordPolicyResponse get() {
        return repository.findTopByDeletedAtIsNullOrderByCreatedAtDesc()
                .map(this::toResponse)
                .orElseGet(this::defaultsResponse);
    }

    @Transactional
    public PasswordPolicyResponse upsert(PasswordPolicyRequest request) {
        PasswordPolicy policy = repository.findTopByDeletedAtIsNullOrderByCreatedAtDesc()
                .orElse(PasswordPolicy.builder().build());

        boolean isNew = policy.getId() == null;
        policy.setMinLength(request.getMinLength());
        policy.setMaxLength(request.getMaxLength());
        policy.setRequireUppercase(request.isRequireUppercase());
        policy.setRequireLowercase(request.isRequireLowercase());
        policy.setRequireNumbers(request.isRequireNumbers());
        policy.setRequireSpecial(request.isRequireSpecial());
        policy.setExpiryDays(request.getExpiryDays());
        policy.setMaxFailedAttempts(request.getMaxFailedAttempts());

        PasswordPolicy saved = repository.save(policy);
        AuditAction action = isNew ? AuditAction.CREATE : AuditAction.UPDATE;
        auditService.log("PasswordPolicy", saved.getId().toString(), action, null, saved);

        // F4-sync delegation. No-ops when the syncer bean isn't a candidate
        // (cia.keycloak.admin.enabled=false). Failures inside the syncer are
        // swallowed there — DB record is the source of truth and the next
        // upsert re-attempts the realm write.
        KeycloakPasswordPolicySyncer s = policySyncer.getIfAvailable();
        if (s != null) {
            s.sync(saved);
        }

        return toResponse(saved);
    }

    private PasswordPolicyResponse toResponse(PasswordPolicy p) {
        return PasswordPolicyResponse.builder()
                .id(p.getId())
                .minLength(p.getMinLength())
                .maxLength(p.getMaxLength())
                .requireUppercase(p.isRequireUppercase())
                .requireLowercase(p.isRequireLowercase())
                .requireNumbers(p.isRequireNumbers())
                .requireSpecial(p.isRequireSpecial())
                .expiryDays(p.getExpiryDays())
                .maxFailedAttempts(p.getMaxFailedAttempts())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    /**
     * Synthetic response carrying the V3 DDL DEFAULTs. id/createdAt/updatedAt
     * stay null so the UI can distinguish "tenant has never configured" from
     * "tenant configured these exact defaults explicitly" — same shape the
     * record will take after the first PUT.
     */
    private PasswordPolicyResponse defaultsResponse() {
        return PasswordPolicyResponse.builder()
                .minLength(DEFAULT_MIN_LENGTH)
                .maxLength(DEFAULT_MAX_LENGTH)
                .requireUppercase(DEFAULT_REQUIRE_UPPERCASE)
                .requireLowercase(DEFAULT_REQUIRE_LOWERCASE)
                .requireNumbers(DEFAULT_REQUIRE_NUMBERS)
                .requireSpecial(DEFAULT_REQUIRE_SPECIAL)
                .expiryDays(DEFAULT_EXPIRY_DAYS)
                .maxFailedAttempts(DEFAULT_MAX_FAILED)
                .build();
    }
}
