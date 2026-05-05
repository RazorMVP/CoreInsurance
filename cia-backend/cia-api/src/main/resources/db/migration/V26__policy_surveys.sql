-- Pre-loss policy survey workflow (B4.3).
--
-- One row per policy. The lifecycle (status) is:
--   ASSIGNED → REPORT_SUBMITTED → APPROVED
--   anything → OVERRIDDEN  (waived with a reason)
--
-- Re-assigning a surveyor mid-cycle just updates the existing row's
-- surveyor_* fields and resets status to ASSIGNED — the unique
-- constraint on policy_id keeps a 1:1 relationship with policies.

CREATE TABLE policy_surveys (
    id                    UUID                     NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(100),
    deleted_at            TIMESTAMP WITH TIME ZONE,

    policy_id             UUID                     NOT NULL,
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

    override_reason       TEXT,
    overridden_by         VARCHAR(100),
    overridden_at         TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_policy_surveys              PRIMARY KEY (id),
    CONSTRAINT uq_policy_surveys_policy       UNIQUE (policy_id),
    CONSTRAINT fk_policy_surveys_policy       FOREIGN KEY (policy_id)
        REFERENCES policies (id) ON DELETE CASCADE
);

CREATE INDEX idx_policy_surveys_policy_id ON policy_surveys (policy_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_policy_surveys_status    ON policy_surveys (status)    WHERE deleted_at IS NULL;
