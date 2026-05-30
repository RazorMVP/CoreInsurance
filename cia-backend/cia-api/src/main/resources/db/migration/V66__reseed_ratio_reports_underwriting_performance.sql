-- V66 — re-seed the 3 ratio reports onto the UNDERWRITING_PERFORMANCE data source
-- so loss_ratio / combined_ratio compute from real premium + claims + expenses.
-- SYSTEM reports are immutable via the service, so this is a data migration.
-- Idempotent: delete the 3 by name (type=SYSTEM) then re-insert. Runs after V18.
-- Non-computed fields are declared in SELECT-column order
-- [class_of_business, premium_earned, claims_incurred, expenses] so the positional
-- applyComputedFields() maps them correctly; computed PERCENT fields appended after.

DELETE FROM report_definition
 WHERE type = 'SYSTEM'
   AND name IN ('Loss Ratio Report', 'Combined Ratio Report', 'Annual Revenue Account (NAICOM)');

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Loss Ratio Report',
  'Loss ratio by class: gross written premium, incurred claims, and computed loss ratio %.',
  'CLAIMS', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium (Gross Written) (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "groupBy":"class_of_business","sortBy":"loss_ratio","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"loss_ratio"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Combined Ratio Report',
  'Loss ratio, expense ratio, and combined ratio by class and period.',
  'FINANCE', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium (Gross Written) (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"expenses","label":"Expenses (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true},
      {"key":"combined_ratio","label":"Combined Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "groupBy":"class_of_business","sortBy":"combined_ratio","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"combined_ratio"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Annual Revenue Account (NAICOM)',
  'Premium earned, claims incurred, expenses, and loss ratio per class — annual statutory format.',
  'REGULATORY', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium Earned (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"expenses","label":"Expenses (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "groupBy":"class_of_business","sortBy":"premium_earned","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium_earned"}
  }',
  false
);
