-- V24: NDPR PII encryption at rest via PostgreSQL pgcrypto
--
-- Encrypts the highest-risk identifying fields on customers and
-- customer_directors:
--   - id_number       (NIN / Voter's Card / Driver's License / Passport)
--   - id_document_url (path to uploaded ID document — knowing it leaks the doc)
--   - address         (residential / business address)
--
-- Other PII fields (first_name, last_name, email, phone, date_of_birth)
-- intentionally remain plain — Customer search uses LIKE on those columns,
-- and substring search on encrypted bytea isn't possible without a
-- companion HMAC-indexed lookup column. That redesign is a follow-up.
--
-- Runtime: Hibernate @ColumnTransformer wraps reads / writes with
-- pgp_sym_decrypt / pgp_sym_encrypt and current_setting('app.pii_key').
-- The session variable is set once per connection by Hikari's
-- connection-init-sql (see application.yml).
--
-- Existing data: this migration encrypts in place using the same
-- app.pii_key the application will use at runtime. Both Flyway and the
-- application share the Hikari pool, so the session var is set on the
-- migration connection too.
--
-- Production rollout note: rotate the key by (a) decrypting with the old
-- key, (b) re-encrypting with the new key in a maintenance window. There
-- is no automated rotation path here yet — that is a follow-up runbook.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE customers
    ALTER COLUMN id_number TYPE bytea
        USING CASE WHEN id_number IS NOT NULL
                   THEN pgp_sym_encrypt(id_number, current_setting('app.pii_key'))
                   ELSE NULL END,
    ALTER COLUMN id_document_url TYPE bytea
        USING CASE WHEN id_document_url IS NOT NULL
                   THEN pgp_sym_encrypt(id_document_url, current_setting('app.pii_key'))
                   ELSE NULL END,
    ALTER COLUMN address TYPE bytea
        USING CASE WHEN address IS NOT NULL
                   THEN pgp_sym_encrypt(address, current_setting('app.pii_key'))
                   ELSE NULL END;

ALTER TABLE customer_directors
    ALTER COLUMN id_number TYPE bytea
        USING CASE WHEN id_number IS NOT NULL
                   THEN pgp_sym_encrypt(id_number, current_setting('app.pii_key'))
                   ELSE NULL END,
    ALTER COLUMN id_document_url TYPE bytea
        USING CASE WHEN id_document_url IS NOT NULL
                   THEN pgp_sym_encrypt(id_document_url, current_setting('app.pii_key'))
                   ELSE NULL END;
