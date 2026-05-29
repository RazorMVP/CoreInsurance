-- V64 — per-RM commission accrual SYSTEM report (Module 11, B2). Aggregates
-- RM-sourced policies by RM over a period (DataSource.RM_COMMISSION) so payroll
-- knows what each RM is owed. total_accrued reconciles with the Cr-2520 postings.
-- The RM_COMMISSION tail is GROUP-BY-only (no ORDER BY), so config MUST set sortBy;
-- no groupBy in config (the GROUP BY rm.name is baked into BASE_QUERY_TAILS) — mirrors
-- the V44 TRIAL_BALANCE convention where the grouping lives in the tail, not the config.
INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RM Commission Accrual',
  'Relationship-Manager commission accrued (Cr 2520) per RM over a period — name, policy count, total premium, total accrued.',
  'FINANCE', 'SYSTEM', 'RM_COMMISSION',
  '{
    "fields": [
      {"key":"relationship_manager_name","label":"Relationship Manager","type":"STRING","computed":false},
      {"key":"policy_count","label":"Policies","type":"INTEGER","computed":false},
      {"key":"total_premium","label":"Total Premium (₦)","type":"MONEY","computed":false},
      {"key":"total_accrued","label":"Total Accrued (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"total_accrued","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"relationship_manager_name","yAxis":"total_accrued"}
  }'
, false);
