-- Module 1: Setup & Administration tables
-- Applied to every tenant schema on startup via Flyway multi-schema migration.

-- -----------------------------------------------------------------------
-- Company & system config
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS company_settings (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(255) NOT NULL,
    rc_number             VARCHAR(50),
    naicom_license_number VARCHAR(50),
    address               TEXT,
    city                  VARCHAR(100),
    state                 VARCHAR(100),
    email                 VARCHAR(255),
    phone                 VARCHAR(30),
    logo_path             VARCHAR(500),
    website               VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(255),
    deleted_at            TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS password_policies (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    min_length           INT         NOT NULL DEFAULT 8,
    max_length           INT         NOT NULL DEFAULT 128,
    require_uppercase    BOOLEAN     NOT NULL DEFAULT TRUE,
    require_lowercase    BOOLEAN     NOT NULL DEFAULT TRUE,
    require_numbers      BOOLEAN     NOT NULL DEFAULT TRUE,
    require_special      BOOLEAN     NOT NULL DEFAULT FALSE,
    expiry_days          INT         NOT NULL DEFAULT 90,
    max_failed_attempts  INT         NOT NULL DEFAULT 5,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(255),
    deleted_at           TIMESTAMPTZ
);

-- -----------------------------------------------------------------------
-- Financial setup
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS banks (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS currencies (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       VARCHAR(3)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    symbol     VARCHAR(10),
    is_default BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

-- -----------------------------------------------------------------------
-- Access control
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS access_groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS access_group_permissions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    access_group_id UUID         NOT NULL REFERENCES access_groups (id),
    permission      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (access_group_id, permission)
);

CREATE INDEX idx_agp_group ON access_group_permissions (access_group_id) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------
-- Approval groups
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS approval_groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS approval_group_levels (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_group_id UUID         NOT NULL REFERENCES approval_groups (id),
    level_order       INT          NOT NULL,
    approver_user_id  VARCHAR(255) NOT NULL,
    approver_name     VARCHAR(255),
    max_amount        NUMERIC(18, 2),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    deleted_at        TIMESTAMPTZ
);

CREATE INDEX idx_agl_group ON approval_group_levels (approval_group_id, level_order) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------
-- Classes of business & products
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS classes_of_business (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS products (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(255)  NOT NULL,
    code                 VARCHAR(20)   NOT NULL UNIQUE,
    class_of_business_id UUID          NOT NULL REFERENCES classes_of_business (id),
    type                 VARCHAR(20)   NOT NULL DEFAULT 'SINGLE_RISK',
    rate                 NUMERIC(10,4) NOT NULL DEFAULT 0,
    min_premium          NUMERIC(18,2) NOT NULL DEFAULT 0,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(255),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX idx_products_class ON products (class_of_business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_active ON products (active) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS product_sections (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID          NOT NULL REFERENCES products (id),
    name       VARCHAR(255)  NOT NULL,
    code       VARCHAR(20)   NOT NULL,
    rate       NUMERIC(10,4) NOT NULL DEFAULT 0,
    order_no   INT           NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ,
    UNIQUE (product_id, code)
);

CREATE INDEX idx_ps_product ON product_sections (product_id) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------
-- Product configuration
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS commission_setups (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id     UUID          NOT NULL REFERENCES products (id),
    broker_type    VARCHAR(50)   NOT NULL DEFAULT 'ALL',
    rate           NUMERIC(6,4)  NOT NULL,
    effective_from DATE          NOT NULL,
    effective_to   DATE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX idx_commission_product ON commission_setups (product_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS policy_specifications (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID        NOT NULL UNIQUE REFERENCES products (id),
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS policy_number_formats (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id           UUID        NOT NULL UNIQUE REFERENCES products (id),
    prefix               VARCHAR(20) NOT NULL,
    include_year         BOOLEAN     NOT NULL DEFAULT TRUE,
    include_class_code   BOOLEAN     NOT NULL DEFAULT TRUE,
    sequence_length      INT         NOT NULL DEFAULT 5,
    last_sequence        BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(255),
    deleted_at           TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS claim_document_requirements (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id     UUID         NOT NULL REFERENCES products (id),
    document_name  VARCHAR(255) NOT NULL,
    is_mandatory   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX idx_cdr_product ON claim_document_requirements (product_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS claim_notification_timelines (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id        UUID        NOT NULL UNIQUE REFERENCES products (id),
    notification_days INT         NOT NULL DEFAULT 7,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    deleted_at        TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS survey_thresholds (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id       UUID          NOT NULL REFERENCES products (id),
    threshold_type   VARCHAR(20)   NOT NULL,
    min_sum_insured  NUMERIC(18,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(255),
    deleted_at       TIMESTAMPTZ,
    UNIQUE (product_id, threshold_type)
);

-- -----------------------------------------------------------------------
-- Loss classification
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS nature_of_loss (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS cause_of_loss (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255) NOT NULL,
    code              VARCHAR(20)  NOT NULL UNIQUE,
    nature_of_loss_id UUID         REFERENCES nature_of_loss (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    deleted_at        TIMESTAMPTZ
);

CREATE INDEX idx_col_nature ON cause_of_loss (nature_of_loss_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS claim_reserve_categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

-- -----------------------------------------------------------------------
-- Organisational entities
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sbus (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS branches (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    sbu_id     UUID         REFERENCES sbus (id),
    address    TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_branches_sbu ON branches (sbu_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS brokers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    rc_number  VARCHAR(50),
    address    TEXT,
    email      VARCHAR(255),
    phone      VARCHAR(30),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS relationship_managers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255),
    phone      VARCHAR(30),
    branch_id  UUID         REFERENCES branches (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_rm_branch ON relationship_managers (branch_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS surveyors (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    type           VARCHAR(20)  NOT NULL DEFAULT 'EXTERNAL',
    license_number VARCHAR(50),
    email          VARCHAR(255),
    phone          VARCHAR(30),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS insurance_companies (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,
    rc_number           VARCHAR(50),
    naicom_license      VARCHAR(50),
    address             TEXT,
    email               VARCHAR(255),
    phone               VARCHAR(30),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(255),
    deleted_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS reinsurance_companies (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    rc_number  VARCHAR(50),
    address    TEXT,
    email      VARCHAR(255),
    phone      VARCHAR(30),
    country    VARCHAR(100) NOT NULL DEFAULT 'Nigeria',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

-- -----------------------------------------------------------------------
-- Vehicle data (motor class)
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS vehicle_makes (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vehicle_models (
    id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    make_id  UUID         NOT NULL REFERENCES vehicle_makes (id),
    name     VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ,
    UNIQUE (make_id, name)
);

CREATE INDEX idx_vmodel_make ON vehicle_models (make_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS vehicle_types (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    deleted_at TIMESTAMPTZ
);
