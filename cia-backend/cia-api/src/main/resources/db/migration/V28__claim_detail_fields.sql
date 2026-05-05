-- Richer claim detail (B7).
--
-- Adds incident-circumstance fields (nature/cause of loss, claimant
-- contact) and Discharge Voucher state (type, amount, generated/executed
-- timestamps) directly to the claims row. These are 1:1 with the claim
-- and were previously only present in the frontend MockClaim shape.
--
-- Comments and required-document checklists remain separate aggregates
-- and are NOT modelled here — they belong in their own slice when the
-- backend grows ClaimComment / ClaimRequiredDocument entities.

ALTER TABLE claims
    ADD COLUMN nature_of_loss   VARCHAR(100),
    ADD COLUMN cause_of_loss    VARCHAR(100),
    ADD COLUMN contact_name     VARCHAR(200),
    ADD COLUMN contact_phone    VARCHAR(50),
    ADD COLUMN dv_type          VARCHAR(20),
    ADD COLUMN dv_amount        NUMERIC(18, 2),
    ADD COLUMN dv_generated_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN dv_executed_at   TIMESTAMP WITH TIME ZONE;
