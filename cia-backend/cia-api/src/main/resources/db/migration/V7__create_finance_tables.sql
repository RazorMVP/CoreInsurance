-- ── Finance number counters ───────────────────────────────────────────────
-- counter_type: DN (debit note), RN (receipt), CN (credit note), PN (payment)
CREATE TABLE finance_counters (
    counter_type    VARCHAR(10)     NOT NULL,
    year            INT             NOT NULL,
    last_sequence   BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_finance_counters PRIMARY KEY (counter_type, year)
);

-- ── Debit notes (receivables — premium due from customer/broker) ──────────
CREATE TABLE debit_notes (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    debit_note_number   VARCHAR(30)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'OUTSTANDING',
    entity_type         VARCHAR(30)     NOT NULL,
    entity_id           UUID            NOT NULL,
    entity_reference    VARCHAR(60)     NOT NULL,

    customer_id         UUID            NOT NULL,
    customer_name       VARCHAR(200)    NOT NULL,
    broker_id           UUID,
    broker_name         VARCHAR(100),
    product_name        VARCHAR(100),

    description         TEXT            NOT NULL,
    amount              DECIMAL(18, 2)  NOT NULL,
    tax_amount          DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    total_amount        DECIMAL(18, 2)  NOT NULL,
    paid_amount         DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code       VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    due_date            DATE,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_debit_notes PRIMARY KEY (id),
    CONSTRAINT uq_debit_notes_number UNIQUE (debit_note_number)
);

CREATE INDEX idx_debit_notes_status      ON debit_notes (status)      WHERE deleted_at IS NULL;
CREATE INDEX idx_debit_notes_customer_id ON debit_notes (customer_id)  WHERE deleted_at IS NULL;
CREATE INDEX idx_debit_notes_entity_id   ON debit_notes (entity_id)    WHERE deleted_at IS NULL;

-- ── Receipts (payment received against a debit note) ──────────────────────
CREATE TABLE receipts (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    receipt_number      VARCHAR(30)     NOT NULL,
    debit_note_id       UUID            NOT NULL REFERENCES debit_notes (id),
    amount              DECIMAL(18, 2)  NOT NULL,
    payment_date        DATE            NOT NULL,
    payment_method      VARCHAR(20)     NOT NULL,
    bank_id             UUID,
    bank_name           VARCHAR(100),
    cheque_number       VARCHAR(50),
    narration           TEXT,
    posted_by           VARCHAR(100),
    status              VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    reversal_reason     TEXT,
    reversed_at         TIMESTAMPTZ,
    reversed_by         VARCHAR(100),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_receipts PRIMARY KEY (id),
    CONSTRAINT uq_receipts_number UNIQUE (receipt_number)
);

CREATE INDEX idx_receipts_debit_note_id ON receipts (debit_note_id) WHERE deleted_at IS NULL;

-- ── Credit notes (payables — claims, commissions, reinsurance outward) ────
CREATE TABLE credit_notes (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    credit_note_number      VARCHAR(30)     NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'OUTSTANDING',
    entity_type             VARCHAR(30)     NOT NULL,
    entity_id               UUID            NOT NULL,
    entity_reference        VARCHAR(60)     NOT NULL,

    beneficiary_id          UUID,
    beneficiary_name        VARCHAR(200)    NOT NULL,

    description             TEXT            NOT NULL,
    amount                  DECIMAL(18, 2)  NOT NULL,
    tax_amount              DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    total_amount            DECIMAL(18, 2)  NOT NULL,
    paid_amount             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code           VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    due_date                DATE,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_credit_notes PRIMARY KEY (id),
    CONSTRAINT uq_credit_notes_number UNIQUE (credit_note_number)
);

CREATE INDEX idx_credit_notes_status      ON credit_notes (status)       WHERE deleted_at IS NULL;
CREATE INDEX idx_credit_notes_entity_id   ON credit_notes (entity_id)    WHERE deleted_at IS NULL;
CREATE INDEX idx_credit_notes_beneficiary ON credit_notes (beneficiary_id) WHERE deleted_at IS NULL;

-- ── Payments (outgoing payment against a credit note) ─────────────────────
CREATE TABLE payments (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    payment_number          VARCHAR(30)     NOT NULL,
    credit_note_id          UUID            NOT NULL REFERENCES credit_notes (id),
    amount                  DECIMAL(18, 2)  NOT NULL,
    payment_date            DATE            NOT NULL,
    payment_method          VARCHAR(20)     NOT NULL,
    bank_id                 UUID,
    bank_name               VARCHAR(100),
    bank_account_name       VARCHAR(200),
    bank_account_number     VARCHAR(50),
    narration               TEXT,
    posted_by               VARCHAR(100),
    status                  VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    reversal_reason         TEXT,
    reversed_at             TIMESTAMPTZ,
    reversed_by             VARCHAR(100),

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_number UNIQUE (payment_number)
);

CREATE INDEX idx_payments_credit_note_id ON payments (credit_note_id) WHERE deleted_at IS NULL;
