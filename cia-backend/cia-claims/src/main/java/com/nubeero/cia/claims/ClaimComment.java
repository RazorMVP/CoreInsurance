package com.nubeero.cia.claims;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "claim_comments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimComment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * Display name resolved at write time so the comment feed can render
     * without an extra Keycloak round-trip per row. {@code createdBy} on
     * BaseEntity holds the auth subject (typically a UUID/username); this
     * captures the human-friendly label.
     */
    @Column(name = "author_name", length = 200)
    private String authorName;
}
