-- V51: snapshot commission source + rate onto policies at issuance.
--
-- Today, commission credit-note generation would have to re-resolve the active
-- CommissionSetup row at settlement time — meaning a rate change months after
-- the policy was issued would silently flow through to the credit note. That's
-- IFRS 17 § B5.5.39 / NAICOM compliance noise we don't want.
--
-- Following the established product-snapshot pattern already used on this
-- table (product_name, product_code, product_rate, class_of_business_*), we
-- snapshot the commission source type + rate at policy creation / quote
-- binding. The credit-note generator (Slice 84c — finding E in the Session 84
-- audit) will read from these columns, not from commission_setups.
--
-- Both columns are nullable on purpose. The Session 84 audit + PRD §2.1.17
-- recognises three CommissionSourceType values — AGENT, BROKER,
-- RELATIONSHIP_MANAGER — but `policies` currently models only broker_id.
-- Agent and relationship-manager attribution at the policy level remains
-- Open Question #11 in PRD v2.7. Until that lands, only broker-attributed
-- policies populate the snapshot; agent / RM policies leave both columns
-- null and fall back to settlement-time resolution (today's behaviour).

ALTER TABLE policies
  ADD COLUMN commission_source_type VARCHAR(30),
  ADD COLUMN commission_rate        DECIMAL(6, 4);

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_commission_source_type
  CHECK (commission_source_type IS NULL
         OR commission_source_type IN ('AGENT', 'BROKER', 'RELATIONSHIP_MANAGER'));

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_commission_pair
  CHECK ((commission_source_type IS NULL AND commission_rate IS NULL)
         OR (commission_source_type IS NOT NULL AND commission_rate IS NOT NULL));
