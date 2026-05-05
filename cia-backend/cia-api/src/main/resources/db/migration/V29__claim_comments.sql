-- Per-claim comment feed (B11).
--
-- Append-only operational notes attached to a claim. Read by claims
-- officers + auditors via the Processing tab on ClaimDetailPage; written
-- via ClaimCommentService.add. Soft-delete via deleted_at is supported
-- (BaseEntity) but not exposed through the controller — comments are an
-- audit trail, not editable correspondence.
--
-- author_name is denormalised so the feed can render without a Keycloak
-- round-trip per row; created_by holds the auth subject.

CREATE TABLE claim_comments (
    id          UUID                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE,
    created_by  VARCHAR(100),
    deleted_at  TIMESTAMP WITH TIME ZONE,

    claim_id    UUID                     NOT NULL,
    body        TEXT                     NOT NULL,
    author_name VARCHAR(200),

    CONSTRAINT pk_claim_comments        PRIMARY KEY (id),
    CONSTRAINT fk_claim_comments_claim  FOREIGN KEY (claim_id)
        REFERENCES claims (id) ON DELETE CASCADE
);

CREATE INDEX idx_claim_comments_claim_created
    ON claim_comments (claim_id, created_at DESC)
    WHERE deleted_at IS NULL;
