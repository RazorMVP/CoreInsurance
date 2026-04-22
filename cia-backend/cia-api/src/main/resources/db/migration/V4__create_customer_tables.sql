-- Module 7: Customer Onboarding & KYC

CREATE TABLE customers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_type           VARCHAR(20)  NOT NULL,           -- INDIVIDUAL | CORPORATE
    customer_status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    kyc_status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    kyc_provider_ref        VARCHAR(100),
    kyc_failure_reason      TEXT,
    kyc_verified_at         TIMESTAMPTZ,

    -- Individual fields
    first_name              VARCHAR(100),
    last_name               VARCHAR(100),
    other_names             VARCHAR(100),
    date_of_birth           DATE,
    gender                  VARCHAR(10),
    marital_status          VARCHAR(20),
    id_type                 VARCHAR(30),                    -- NIN | VOTERS_CARD | DRIVERS_LICENSE | PASSPORT
    id_number               VARCHAR(50),

    -- Corporate fields
    company_name            VARCHAR(200),
    rc_number               VARCHAR(50),
    incorporation_date      DATE,
    industry                VARCHAR(100),
    contact_person          VARCHAR(200),

    -- Common contact & address
    email                   VARCHAR(200),
    phone                   VARCHAR(30),
    alternate_phone         VARCHAR(30),
    address                 TEXT,
    city                    VARCHAR(100),
    state                   VARCHAR(100),
    country                 VARCHAR(100) NOT NULL DEFAULT 'Nigeria',

    -- Blacklist info
    blacklist_reason        TEXT,
    blacklisted_at          TIMESTAMPTZ,
    blacklisted_by          VARCHAR(100),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ
);

CREATE INDEX idx_customers_type       ON customers (customer_type)   WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_status     ON customers (customer_status) WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_kyc_status ON customers (kyc_status)      WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_email      ON customers (email)           WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_phone      ON customers (phone)           WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_rc_number  ON customers (rc_number)       WHERE deleted_at IS NULL;

CREATE TABLE customer_directors (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customers (id),
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    date_of_birth       DATE,
    id_type             VARCHAR(30),
    id_number           VARCHAR(50),
    kyc_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    kyc_provider_ref    VARCHAR(100),
    kyc_failure_reason  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_customer_directors_customer ON customer_directors (customer_id) WHERE deleted_at IS NULL;

CREATE TABLE customer_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customers (id),
    document_type   VARCHAR(50) NOT NULL,
    document_name   VARCHAR(200) NOT NULL,
    document_path   TEXT NOT NULL,
    mime_type       VARCHAR(100),
    file_size_bytes BIGINT,
    uploaded_by     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_customer_documents_customer ON customer_documents (customer_id) WHERE deleted_at IS NULL;
