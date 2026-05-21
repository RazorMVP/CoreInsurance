-- ─────────────────────────────────────────────────────────────────────────────
-- V44 — Seed 12 SYSTEM CLOSURES report definitions
--
-- Module 11 (Reports & Analytics) extension for Module 12 (Period-End Closures).
-- Adds default SYSTEM reports across the GL, IFRS 17 PAA, and IFRS 9 substrates
-- shipped in Phases 1-3 (V31-V40). The 8 NAICOM N01-N08 reports already ship as
-- REGULATORY SYSTEM reports in V18; the gap closed here is GL + IFRS 17 + IFRS 9.
--
-- All 12 are pinnable (is_pinnable=TRUE) — these are operational ledger queries
-- finance and CFO will run repeatedly, not regulator-mandated forms.
--
-- Access is NOT seeded here (matches V18). System Admin grants per access group
-- via the Reports → Setup UI. Required Spring authorities to consume:
--   - reports:view         — run + read result
--   - reports:export_csv   — download CSV
--   - reports:export_pdf   — download PDF
--   - reports:create_custom — clone any of these into a CUSTOM report
-- ─────────────────────────────────────────────────────────────────────────────

-- ═══════════════════════════════════════════════════════════════════════════
-- GL Foundation (4 reports — Phase 1 substrate V31)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Trial Balance',
  'Cumulative debit, credit, and net balance per chart-of-account up to a chosen date. Aggregates POSTED journal entry lines via TrialBalanceService substrate.',
  'CLOSURES', 'SYSTEM', 'TRIAL_BALANCE',
  '{
    "fields": [
      {"key":"account_code","label":"Account Code","type":"STRING","computed":false},
      {"key":"account_name","label":"Account","type":"STRING","computed":false},
      {"key":"account_type","label":"Type","type":"STRING","computed":false},
      {"key":"total_debit","label":"Debit (₦)","type":"MONEY","computed":false},
      {"key":"total_credit","label":"Credit (₦)","type":"MONEY","computed":false},
      {"key":"net_balance","label":"Net (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"From Date","type":"DATE","required":false},
      {"key":"date_to","label":"As Of","type":"DATE","required":true}
    ],
    "sortBy":"account_code","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'General Journal Listing',
  'Per-line journal entry register with COA, class-of-business, and source-module resolution. Filterable by date, status, and class.',
  'CLOSURES', 'SYSTEM', 'GENERAL_LEDGER',
  '{
    "fields": [
      {"key":"business_date","label":"Business Date","type":"DATE","computed":false},
      {"key":"source_module","label":"Source","type":"STRING","computed":false},
      {"key":"source_event_type","label":"Event","type":"STRING","computed":false},
      {"key":"source_reference","label":"Reference","type":"STRING","computed":false},
      {"key":"account_code","label":"Account Code","type":"STRING","computed":false},
      {"key":"account_name","label":"Account","type":"STRING","computed":false},
      {"key":"debit_amount","label":"Debit (₦)","type":"MONEY","computed":false},
      {"key":"credit_amount","label":"Credit (₦)","type":"MONEY","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"From Date","type":"DATE","required":true},
      {"key":"date_to","label":"To Date","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"business_date","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Account Movement Statement',
  'Per-account journal entry ledger between two dates. The account_code filter scopes to a single COA leaf.',
  'CLOSURES', 'SYSTEM', 'GENERAL_LEDGER',
  '{
    "fields": [
      {"key":"business_date","label":"Business Date","type":"DATE","computed":false},
      {"key":"source_module","label":"Source","type":"STRING","computed":false},
      {"key":"source_event_type","label":"Event","type":"STRING","computed":false},
      {"key":"source_reference","label":"Reference","type":"STRING","computed":false},
      {"key":"debit_amount","label":"Debit (₦)","type":"MONEY","computed":false},
      {"key":"credit_amount","label":"Credit (₦)","type":"MONEY","computed":false},
      {"key":"narrative","label":"Narrative","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"account_code","label":"Account Code","type":"TEXT","required":true},
      {"key":"date_from","label":"From Date","type":"DATE","required":true},
      {"key":"date_to","label":"To Date","type":"DATE","required":true}
    ],
    "sortBy":"business_date","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Period Lock Audit Trail',
  'Soft-close, hard-close, and release events across all fiscal periods. Each lock change appends a row (Type-2 SCD timeline per Slice 1.7).',
  'CLOSURES', 'SYSTEM', 'GL_PERIOD_LOCK',
  '{
    "fields": [
      {"key":"period_start","label":"Period Start","type":"DATE","computed":false},
      {"key":"period_end","label":"Period End","type":"DATE","computed":false},
      {"key":"period_type","label":"Type","type":"STRING","computed":false},
      {"key":"lock_type","label":"Lock","type":"STRING","computed":false},
      {"key":"locked_at","label":"Locked At","type":"DATE","computed":false},
      {"key":"locked_by","label":"Locked By","type":"STRING","computed":false},
      {"key":"released_at","label":"Released At","type":"DATE","computed":false},
      {"key":"released_by","label":"Released By","type":"STRING","computed":false},
      {"key":"release_reason","label":"Release Reason","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"From Date","type":"DATE","required":false},
      {"key":"date_to","label":"To Date","type":"DATE","required":false},
      {"key":"status","label":"Lock Type","type":"SELECT","required":false}
    ],
    "sortBy":"locked_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- IFRS 17 PAA (4 reports — Phase 2 substrate V36 + V38 view)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'LRC Roll-forward Schedule',
  'IFRS 17 §103 disclosure — Liability for Remaining Coverage roll-forward per portfolio × cohort × onerousness. Relays the V38 paa_movement_analysis view.',
  'CLOSURES', 'SYSTEM', 'IFRS17_MOVEMENT',
  '{
    "fields": [
      {"key":"portfolio_name","label":"Portfolio","type":"STRING","computed":false},
      {"key":"cohort_year","label":"Cohort","type":"INTEGER","computed":false},
      {"key":"onerousness","label":"Onerousness","type":"STRING","computed":false},
      {"key":"lrc_opening","label":"Opening (₦)","type":"MONEY","computed":false},
      {"key":"premium_received","label":"Premium Rcvd","type":"MONEY","computed":false},
      {"key":"premium_earned","label":"Premium Earned","type":"MONEY","computed":false},
      {"key":"acquisition_costs_deferred","label":"Acq Deferred","type":"MONEY","computed":false},
      {"key":"acquisition_costs_amortised","label":"Acq Amortised","type":"MONEY","computed":false},
      {"key":"loss_component_change","label":"Loss Comp Δ","type":"MONEY","computed":false},
      {"key":"lrc_closing","label":"Closing (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Period From","type":"DATE","required":true},
      {"key":"date_to","label":"Period To","type":"DATE","required":true},
      {"key":"status","label":"Group Status","type":"SELECT","required":false}
    ],
    "sortBy":"portfolio_name","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'LIC Roll-forward Schedule',
  'IFRS 17 §103 disclosure — Liability for Incurred Claims roll-forward per portfolio × cohort. Relays the V38 paa_movement_analysis view.',
  'CLOSURES', 'SYSTEM', 'IFRS17_MOVEMENT',
  '{
    "fields": [
      {"key":"portfolio_name","label":"Portfolio","type":"STRING","computed":false},
      {"key":"cohort_year","label":"Cohort","type":"INTEGER","computed":false},
      {"key":"lic_opening","label":"Opening (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Incurred","type":"MONEY","computed":false},
      {"key":"claims_paid","label":"Paid","type":"MONEY","computed":false},
      {"key":"case_reserve_change","label":"Case Δ","type":"MONEY","computed":false},
      {"key":"ibnr_change","label":"IBNR Δ","type":"MONEY","computed":false},
      {"key":"risk_adjustment_change","label":"RA Δ","type":"MONEY","computed":false},
      {"key":"discount_unwind","label":"Discount Unwind","type":"MONEY","computed":false},
      {"key":"lic_closing","label":"Closing (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Period From","type":"DATE","required":true},
      {"key":"date_to","label":"Period To","type":"DATE","required":true}
    ],
    "sortBy":"portfolio_name","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Insurance Service Result Summary',
  'IFRS 17 §83 / §84 Insurance Service Result per portfolio × cohort — premium earned (insurance revenue) less claims incurred, acquisition amortisation, and onerous loss-component change.',
  'CLOSURES', 'SYSTEM', 'IFRS17_MOVEMENT',
  '{
    "fields": [
      {"key":"portfolio_name","label":"Portfolio","type":"STRING","computed":false},
      {"key":"cohort_year","label":"Cohort","type":"INTEGER","computed":false},
      {"key":"premium_earned","label":"Insurance Revenue","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred","type":"MONEY","computed":false},
      {"key":"acquisition_costs_amortised","label":"Acq Amortised","type":"MONEY","computed":false},
      {"key":"loss_component_change","label":"Onerous Loss Δ","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Period From","type":"DATE","required":true},
      {"key":"date_to","label":"Period To","type":"DATE","required":true}
    ],
    "sortBy":"portfolio_name","sortDir":"ASC",
    "chart":{"type":"BAR","xAxis":"portfolio_name","yAxis":"premium_earned"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Contract Groups Listing',
  'IFRS 17 §22 contract groups — one row per portfolio × cohort_year × onerousness bucket. Assignment is permanent at initial recognition.',
  'CLOSURES', 'SYSTEM', 'PAA_GROUPS',
  '{
    "fields": [
      {"key":"portfolio_code","label":"Portfolio Code","type":"STRING","computed":false},
      {"key":"portfolio_name","label":"Portfolio","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"cohort_year","label":"Cohort","type":"INTEGER","computed":false},
      {"key":"onerousness","label":"Onerousness","type":"STRING","computed":false},
      {"key":"group_status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Created","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"status","label":"Status","type":"SELECT","required":false},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"cohort_year","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- IFRS 9 (4 reports — Phase 3 substrate V39 + V40 view)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Investment Holdings Schedule',
  'IFRS 9 financial assets currently held, grouped by classification (Amortised Cost / FVOCI debt / FVOCI equity / FVPL). Pre-classification routing per §4.1 + §B4.1.26.',
  'CLOSURES', 'SYSTEM', 'IFRS9_HOLDINGS',
  '{
    "fields": [
      {"key":"isin","label":"ISIN","type":"STRING","computed":false},
      {"key":"security_name","label":"Security","type":"STRING","computed":false},
      {"key":"issuer","label":"Issuer","type":"STRING","computed":false},
      {"key":"asset_type","label":"Asset Type","type":"STRING","computed":false},
      {"key":"classification","label":"Classification","type":"STRING","computed":false},
      {"key":"acquisition_date","label":"Acquired","type":"DATE","computed":false},
      {"key":"acquisition_cost","label":"Cost (₦)","type":"MONEY","computed":false},
      {"key":"face_value","label":"Face (₦)","type":"MONEY","computed":false},
      {"key":"coupon_rate","label":"Coupon %","type":"PERCENT","computed":false},
      {"key":"maturity_date","label":"Maturity","type":"DATE","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"ecl_stage","label":"ECL Stage","type":"INTEGER","computed":false}
    ],
    "filters": [
      {"key":"classification","label":"Classification","type":"SELECT","required":false},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "groupBy":"classification",
    "sortBy":"classification","sortDir":"ASC",
    "chart":{"type":"PIE","xAxis":"classification","yAxis":"acquisition_cost"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Investment Carrying Value Movement',
  'IFRS 9 per-holding period-end roll-forward — opening → effective interest → fair-value change (P&L / OCI) → ECL → closing. Source: investment_carrying_value × investment_holding.',
  'CLOSURES', 'SYSTEM', 'IFRS9_CARRYING',
  '{
    "fields": [
      {"key":"period_start","label":"Period Start","type":"DATE","computed":false},
      {"key":"isin","label":"ISIN","type":"STRING","computed":false},
      {"key":"security_name","label":"Security","type":"STRING","computed":false},
      {"key":"classification","label":"Classification","type":"STRING","computed":false},
      {"key":"opening_balance","label":"Opening (₦)","type":"MONEY","computed":false},
      {"key":"effective_interest_income","label":"Interest","type":"MONEY","computed":false},
      {"key":"fair_value_change_pnl","label":"FV Δ P&L","type":"MONEY","computed":false},
      {"key":"fair_value_change_oci","label":"FV Δ OCI","type":"MONEY","computed":false},
      {"key":"ecl_movement","label":"ECL Δ","type":"MONEY","computed":false},
      {"key":"closing_balance","label":"Closing (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Period From","type":"DATE","required":true},
      {"key":"date_to","label":"Period To","type":"DATE","required":true},
      {"key":"classification","label":"Classification","type":"SELECT","required":false}
    ],
    "sortBy":"period_start","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Premium Receivable ECL Schedule',
  'IFRS 9 §5.5.15 simplified-approach ECL postings on premium receivables. Filter source_module to PremiumReceivableEclEngine to scope to ECL journal entries; the provision matrix is embedded in each entry narrative as the disclosure substrate.',
  'CLOSURES', 'SYSTEM', 'GENERAL_LEDGER',
  '{
    "fields": [
      {"key":"business_date","label":"Business Date","type":"DATE","computed":false},
      {"key":"source_event_type","label":"Event","type":"STRING","computed":false},
      {"key":"source_reference","label":"Reference","type":"STRING","computed":false},
      {"key":"account_code","label":"Account","type":"STRING","computed":false},
      {"key":"account_name","label":"Account Name","type":"STRING","computed":false},
      {"key":"debit_amount","label":"Debit (₦)","type":"MONEY","computed":false},
      {"key":"credit_amount","label":"Credit (₦)","type":"MONEY","computed":false},
      {"key":"narrative","label":"Provision Matrix","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"From Date","type":"DATE","required":true},
      {"key":"date_to","label":"To Date","type":"DATE","required":true},
      {"key":"source_module","label":"Source Module","type":"TEXT","required":true}
    ],
    "sortBy":"business_date","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  '§B5.5.39 Combined Movement Analysis',
  'IFRS 9 §B5.5.39 / IFRS 7 §35M combined movement analysis — per-holding period roll-forward consolidating AC, FVOCI debt, FVOCI equity, and FVPL classifications. Relays the V40 ifrs9_investment_movement_analysis view.',
  'CLOSURES', 'SYSTEM', 'IFRS9_MOVEMENT',
  '{
    "fields": [
      {"key":"period_start","label":"Period Start","type":"DATE","computed":false},
      {"key":"isin","label":"ISIN","type":"STRING","computed":false},
      {"key":"security_name","label":"Security","type":"STRING","computed":false},
      {"key":"asset_type","label":"Asset","type":"STRING","computed":false},
      {"key":"classification","label":"Classification","type":"STRING","computed":false},
      {"key":"opening_balance","label":"Opening (₦)","type":"MONEY","computed":false},
      {"key":"total_pnl_income","label":"P&L Income","type":"MONEY","computed":false},
      {"key":"total_oci_movement","label":"OCI Δ","type":"MONEY","computed":false},
      {"key":"ecl_movement","label":"ECL Δ","type":"MONEY","computed":false},
      {"key":"disposals","label":"Disposals","type":"MONEY","computed":false},
      {"key":"closing_balance","label":"Closing (₦)","type":"MONEY","computed":false},
      {"key":"ecl_stage","label":"Stage","type":"INTEGER","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Period From","type":"DATE","required":true},
      {"key":"date_to","label":"Period To","type":"DATE","required":true},
      {"key":"classification","label":"Classification","type":"SELECT","required":false}
    ],
    "sortBy":"period_start","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);
