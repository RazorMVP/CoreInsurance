package com.nubeero.cia.setup.user.dto;

import com.nubeero.cia.setup.user.UserStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Mirror of cia-frontend's UserDto. Sourced from Keycloak's
 * UserRepresentation + a join against access_groups for the human-readable
 * access group name.
 */
@Data
@Builder
public class UserResponse {
    private String     id;
    private String     email;
    private String     firstName;
    private String     lastName;
    /** ACTIVE = enabled; INACTIVE = !enabled; LOCKED = brute-force locked. */
    private UserStatus status;
    /** Stored as a Keycloak user attribute ({@code accessGroupId}). */
    private String     accessGroupId;
    /** Resolved by joining {@code accessGroupId} against {@code access_groups}. */
    private String     accessGroupName;
    /** Keycloak's {@code createdTimestamp} (ms since epoch) → ISO instant. */
    private Instant    createdAt;
}
