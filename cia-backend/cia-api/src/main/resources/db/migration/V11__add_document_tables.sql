-- ── Document templates ───────────────────────────────────────────────────
-- Admins upload HTML templates per document type (and optionally per product/class).
-- If no tenant template exists, the service falls back to a classpath default.
CREATE TABLE document_templates (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    template_type           VARCHAR(40)     NOT NULL,
    product_id              UUID,
    class_of_business_id    UUID,
    storage_path            VARCHAR(500)    NOT NULL,
    description             TEXT,
    active                  BOOLEAN         NOT NULL DEFAULT true,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_document_templates PRIMARY KEY (id)
);

CREATE INDEX idx_doc_tpl_type    ON document_templates (template_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_doc_tpl_product ON document_templates (product_id)    WHERE deleted_at IS NULL;
CREATE INDEX idx_doc_tpl_cob     ON document_templates (class_of_business_id) WHERE deleted_at IS NULL;

-- ── Add document path to endorsements ────────────────────────────────────
ALTER TABLE endorsements
    ADD COLUMN document_path VARCHAR(500);

-- ── Add discharge voucher document path to claims ─────────────────────────
ALTER TABLE claims
    ADD COLUMN dv_document_path VARCHAR(500);
