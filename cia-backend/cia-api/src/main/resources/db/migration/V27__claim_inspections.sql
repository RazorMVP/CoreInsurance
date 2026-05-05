-- Post-loss claim inspection workflow (B6.1).
--
-- One row per claim. Lifecycle:
--   ASSIGNED → REPORT_SUBMITTED → APPROVED
--   REPORT_SUBMITTED → DECLINED → ASSIGNED (re-assigned + new report)
--   any → OVERRIDDEN (waived with reason)
--
-- Differs from policy_surveys (V26) by the DECLINED non-terminal state —
-- post-loss inspection reports commonly get bounced and re-submitted.
-- Re-assigning a surveyor mid-cycle just updates the existing row's
-- surveyor_* fields and resets status to ASSIGNED — the unique
-- constraint on claim_id keeps a 1:1 relationship with claims.

CREATE TABLE claim_inspections (
    id                    UUID                     NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(100),
    deleted_at            TIMESTAMP WITH TIME ZONE,

    claim_id              UUID                     NOT NULL,
    status                VARCHAR(30)              NOT NULL,

    surveyor_type         VARCHAR(20),
    surveyor_id           UUID,
    surveyor_name         VARCHAR(200),
    assigned_by           VARCHAR(100),
    assigned_at           TIMESTAMP WITH TIME ZONE,

    report_path           VARCHAR(500),
    report_notes          TEXT,
    report_uploaded_by    VARCHAR(100),
    report_uploaded_at    TIMESTAMP WITH TIME ZONE,

    approved_by           VARCHAR(100),
    approved_at           TIMESTAMP WITH TIME ZONE,
    approval_notes        TEXT,

    declined_by           VARCHAR(100),
    declined_at           TIMESTAMP WITH TIME ZONE,
    decline_reason        TEXT,

    override_reason       TEXT,
    overridden_by         VARCHAR(100),
    overridden_at         TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_claim_inspections        PRIMARY KEY (id),
    CONSTRAINT uq_claim_inspections_claim  UNIQUE (claim_id),
    CONSTRAINT fk_claim_inspections_claim  FOREIGN KEY (claim_id)
        REFERENCES claims (id) ON DELETE CASCADE
);

CREATE INDEX idx_claim_inspections_claim_id ON claim_inspections (claim_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_claim_inspections_status   ON claim_inspections (status)   WHERE deleted_at IS NULL;
