-- V19: Add KYC document upload fields to customers and customer_directors
-- Individual KYC: id_document_url (required), id_expiry_date (required for DL/Passport)
-- Corporate KYC:  cac_certificate_url (required), cac_issued_date (required)
-- Director KYC:   id_document_url (required), id_expiry_date (required for DL/Passport)

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS id_document_url   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS id_expiry_date    DATE,
    ADD COLUMN IF NOT EXISTS cac_certificate_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS cac_issued_date   DATE;

ALTER TABLE customer_directors
    ADD COLUMN IF NOT EXISTS id_document_url   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS id_expiry_date    DATE;
