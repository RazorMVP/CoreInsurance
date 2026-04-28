-- Quote configuration tables (discount types, loading types, global quote settings)
-- Applied to every tenant schema.

-- ── Discount types (named labels; rate set by underwriter at quote time) ──────
CREATE TABLE quote_discount_types (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(100),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT pk_quote_discount_types PRIMARY KEY (id),
    CONSTRAINT uq_quote_discount_types_name UNIQUE (name)
);

-- ── Loading types (named labels; rate set by underwriter at quote time) ────────
CREATE TABLE quote_loading_types (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(100),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT pk_quote_loading_types PRIMARY KEY (id),
    CONSTRAINT uq_quote_loading_types_name UNIQUE (name)
);

-- ── Quote configuration singleton ─────────────────────────────────────────────
-- One row per tenant. validity_days drives the PDF subjectivity statement.
-- calc_sequence controls whether loading or discount is applied first.
CREATE TABLE quote_config (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    validity_days   INT         NOT NULL DEFAULT 30,
    calc_sequence   VARCHAR(30) NOT NULL DEFAULT 'LOADING_FIRST',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT pk_quote_config PRIMARY KEY (id)
);

-- Seed default config row (insert-if-empty on startup is handled by QuoteConfigService,
-- but the Flyway seed avoids a missing-row edge case on first launch).
INSERT INTO quote_config (validity_days, calc_sequence)
VALUES (30, 'LOADING_FIRST');

-- Seed common discount types
INSERT INTO quote_discount_types (name) VALUES
    ('No Claims Discount (NCD)'),
    ('Fleet Discount'),
    ('Loyalty Discount'),
    ('Group Scheme Discount'),
    ('Long-term Policy Discount');

-- Seed common loading types
INSERT INTO quote_loading_types (name) VALUES
    ('Adverse Risk Loading'),
    ('High Claims History'),
    ('Occupation Loading'),
    ('Age Loading'),
    ('Location Hazard Loading');
