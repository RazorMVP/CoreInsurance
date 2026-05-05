-- Required-document checklist type linkage (B12).
--
-- Adds a document_type column to claim_document_requirements so that the
-- per-claim "missing documents" view can match a setup-defined requirement
-- to an actual uploaded ClaimDocument by its ClaimDocumentType enum value.
--
-- Allowed enum values (validated in cia-setup at write time):
--   CLAIM_FORM, POLICE_REPORT, SURVEY_REPORT, MEDICAL_REPORT,
--   PHOTOS, REPAIR_ESTIMATE, DISCHARGE_VOUCHER, OTHER
--
-- The column is nullable because requirements seeded before B12 do not
-- carry a type. Such rows show as "received: false, mappable: false" in
-- the per-claim checklist until an admin updates them via setup.

ALTER TABLE claim_document_requirements
    ADD COLUMN document_type VARCHAR(50);
