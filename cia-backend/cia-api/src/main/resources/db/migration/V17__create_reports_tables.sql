-- Module 11: Reports & Analytics — report_definition, report_pin, report_access_policy

CREATE TABLE report_definition (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    category      VARCHAR(50)  NOT NULL,  -- UNDERWRITING | CLAIMS | FINANCE | REINSURANCE | CUSTOMER | REGULATORY
    type          VARCHAR(20)  NOT NULL,  -- SYSTEM | CUSTOM
    data_source   VARCHAR(50)  NOT NULL,  -- POLICIES | CLAIMS | FINANCE | REINSURANCE | CUSTOMERS | ENDORSEMENTS
    config        JSONB        NOT NULL,
    is_pinnable   BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_report_def_category ON report_definition (category);
CREATE INDEX idx_report_def_type     ON report_definition (type);
CREATE INDEX idx_report_def_active   ON report_definition (is_active) WHERE is_active = TRUE;

-- ── report_pin ────────────────────────────────────────────────────────────────

CREATE TABLE report_pin (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id       VARCHAR(255) NOT NULL,
    report_id     UUID        NOT NULL REFERENCES report_definition(id) ON DELETE CASCADE,
    display_order INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_pin_user_report UNIQUE (user_id, report_id)
);

CREATE INDEX idx_report_pin_user ON report_pin (user_id);

-- ── report_access_policy ──────────────────────────────────────────────────────

CREATE TABLE report_access_policy (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    access_group_id  UUID        NOT NULL,
    category         VARCHAR(50),         -- NULL = report-level only
    report_id        UUID        REFERENCES report_definition(id) ON DELETE CASCADE,
    can_view         BOOLEAN     NOT NULL DEFAULT FALSE,
    can_export_csv   BOOLEAN     NOT NULL DEFAULT FALSE,
    can_export_pdf   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_category_or_report CHECK (category IS NOT NULL OR report_id IS NOT NULL)
);

CREATE INDEX idx_report_access_group ON report_access_policy (access_group_id);
