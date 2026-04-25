-- Seed 55 SYSTEM report definitions for Module 11: Reports & Analytics
-- Safe to run multiple times — DELETE+INSERT on name+type ensures idempotency

DELETE FROM report_definition WHERE type = 'SYSTEM';

-- ═══════════════════════════════════════════════════════════════════════════
-- UNDERWRITING (12 reports — U01–U12)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Gross Written Premium',
  'Total premium written by period, class of business, product, and branch.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"product_name","label":"Product","type":"STRING","computed":false},
      {"key":"premium","label":"GWP (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false},
      {"key":"product_id","label":"Product","type":"MULTI_SELECT","required":false}
    ],
    "groupBy":"class_of_business","sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Net Written Premium',
  'Gross Written Premium less reinsurance ceded, by class.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium","label":"GWP (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "groupBy":"class_of_business","sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Premium Earned vs Unearned',
  'Earned and unearned premium split by class and policy count.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "groupBy":"class_of_business","sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Policy Register',
  'Full list of policies with class, product, sum insured, premium, status, and expiry.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"product_name","label":"Product","type":"STRING","computed":false},
      {"key":"sum_insured","label":"Sum Insured (₦)","type":"MONEY","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"end_date","label":"Expiry","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false},
      {"key":"product_id","label":"Product","type":"MULTI_SELECT","required":false},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Renewal Due Report',
  'Policies approaching expiry — by class, product, and renewal status.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"end_date","label":"Expiry","type":"DATE","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Expiry From","type":"DATE","required":true},
      {"key":"date_to","label":"Expiry To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"end_date","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Quote-to-Bind Conversion',
  'Quotes raised vs converted to policy, with lapse and conversion percentage.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false},
      {"key":"conversion_pct","label":"Conversion %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"conversion_pct","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"conversion_pct"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'New Business vs Renewal',
  'New GWP versus renewal GWP with retention rate by class.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Policy Lapse Report',
  'Lapsed policies with premium at risk and lapse rate by class.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium","label":"Premium at Risk (₦)","type":"MONEY","computed":false},
      {"key":"end_date","label":"Lapse Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"end_date","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Debit Note Analysis',
  'Debit notes by policy, customer, amount, status, and due date.',
  'UNDERWRITING', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"DN No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"due_date","label":"Due Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "sortBy":"due_date","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Sum Insured Exposure',
  'Total sum insured exposure by class and product with policy count.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"product_name","label":"Product","type":"STRING","computed":false},
      {"key":"sum_insured","label":"Total SI (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"sum_insured","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"sum_insured"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Coinsurance Report',
  'Coinsurance policies showing type, share percentage, and co-insurer.',
  'UNDERWRITING', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"sum_insured","label":"Sum Insured (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Premium Instalment Schedule',
  'Outstanding instalment payments by policy, customer, amount, and due date.',
  'UNDERWRITING', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"DN No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"amount","label":"Instalment (₦)","type":"MONEY","computed":false},
      {"key":"due_date","label":"Due Date","type":"DATE","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "sortBy":"due_date","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- CLAIMS (13 reports — C01–C13)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims Register',
  'Full claims register with policy, customer, class, status, reserve, paid, and incurred.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"total_paid","label":"Paid (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Loss Ratio Report',
  'Loss ratio by class: premium earned, claims incurred, and computed loss ratio %.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
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
  'Claims Incurred Report',
  'Claims opened, reserves set, expenses, and total incurred by period.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"total_paid","label":"Paid (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"LINE","xAxis":"registered_at","yAxis":"reserve_amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Outstanding Claims',
  'Open claims with age in days, reserve, class, and current status.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"registered_at","label":"Registered","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"registered_at","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims Settled',
  'Settled claims with settlement amount, DV number, and settlement date.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"total_paid","label":"Settlement (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims Ageing Report',
  'Claims grouped into 0–30, 31–60, 61–90, and 90+ day ageing buckets.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"registered_at","label":"Registered","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"ASC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"reserve_amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Reserve Movement',
  'Opening reserve, additions, releases, and closing reserve per claim.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"total_paid","label":"Paid (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims Expense Report',
  'Claim expenses by type, amount, and approving user.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Expenses (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Large Loss Report',
  'Claims exceeding a configurable minimum incurred amount threshold.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Incurred (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"reserve_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims by Nature / Cause of Loss',
  'Claim frequency and total incurred grouped by nature and cause of loss.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Incurred (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "sortBy":"reserve_amount","sortDir":"DESC",
    "chart":{"type":"PIE","xAxis":"class_of_business","yAxis":"reserve_amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Recovery Report',
  'Reinsurance and salvage recoveries on claims — amount and date.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"total_paid","label":"Recovery (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Survey / Inspection Backlog',
  'Claims with loss inspection pending — days outstanding and assigned surveyor.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"registered_at","label":"Registered","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Missing Documents Report',
  'Claims with outstanding required documents — names and days overdue.',
  'CLAIMS', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"registered_at","label":"Registered","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- FINANCE (9 reports — F01–F09)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Receivables Ageing',
  'Outstanding debit notes by ageing bucket — 0–30, 31–60, 61–90, 90+ days.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"DN No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"due_date","label":"Due Date","type":"DATE","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"due_date","sortDir":"ASC",
    "chart":{"type":"BAR","xAxis":"customer_name","yAxis":"amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Collections Report',
  'Receipts posted by date, customer, debit note, amount, and payment method.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"DN No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"LINE","xAxis":"created_at","yAxis":"amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Outstanding Premium',
  'Unpaid debit notes with policy details and days overdue.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"DN No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"due_date","label":"Due Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"due_date","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Payables Report',
  'Credit notes payable — beneficiary, type, amount, and status.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"CN No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Beneficiary","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Payments Report',
  'Payments processed — credit note reference, beneficiary, amount, method, and date.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"CN No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Beneficiary","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"LINE","xAxis":"created_at","yAxis":"amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Commission Statement',
  'Broker commission — policy count, GWP, commission rate, and amount by broker.',
  'FINANCE', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"customer_name","label":"Broker","type":"STRING","computed":false},
      {"key":"premium","label":"GWP (₦)","type":"MONEY","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"customer_name","yAxis":"premium"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Cash Flow Report',
  'Expected inflows, outflows, and net cash flow by period.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"Reference","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"ASC",
    "chart":{"type":"LINE","xAxis":"created_at","yAxis":"amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Combined Ratio Report',
  'Loss ratio, expense ratio, and combined ratio by class and period.',
  'FINANCE', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
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
  'Bank Reconciliation Summary',
  'Receipts posted and payments made against system balance by period.',
  'FINANCE', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"Reference","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- REINSURANCE (8 reports — R01–R08)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RI Premium Bordereaux',
  'Policy-level ceded premium by treaty and reinsurer.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Ceded Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RI Claims Bordereaux',
  'Claim-level ceded losses by treaty and reinsurer.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Ceded Loss (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Treaty Utilisation',
  'Capacity used and remaining by treaty — with utilisation percentage.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"treaty_type","label":"Type","type":"STRING","computed":false},
      {"key":"retained_amount","label":"Retained (₦)","type":"MONEY","computed":false},
      {"key":"ceded_amount","label":"Ceded (₦)","type":"MONEY","computed":false},
      {"key":"utilisation_pct","label":"Utilisation %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"utilisation_pct","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"treaty_name","yAxis":"utilisation_pct"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Facultative Register',
  'All outward and inward facultative placements with status and share percentage.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"treaty_name","label":"Reinsurer","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Ceded (₦)","type":"MONEY","computed":false},
      {"key":"treaty_type","label":"Type","type":"STRING","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"status","label":"Status","type":"SELECT","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RI Recovery Report',
  'Reinsurance recoveries on claims — by treaty, reinsurer, and date.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Recovery (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Cession Statement',
  'Ceded premium, ceded claims, and balance by reinsurer.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"treaty_name","label":"Reinsurer","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Ceded Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"treaty_name","yAxis":"ceded_amount"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Retention vs Cession Summary',
  'Retained vs ceded sum insured by class with retention percentage.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"retained_amount","label":"Retained (₦)","type":"MONEY","computed":false},
      {"key":"ceded_amount","label":"Ceded (₦)","type":"MONEY","computed":false},
      {"key":"cession_pct","label":"Cession %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"cession_pct","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"treaty_name","yAxis":"cession_pct"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Excess Capacity Report',
  'Policies exceeding treaty gross capacity — with facultative placement status.',
  'REINSURANCE', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"retained_amount","label":"Retained (₦)","type":"MONEY","computed":false},
      {"key":"ceded_amount","label":"Ceded (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"FAC Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- CUSTOMER (5 reports — K01–K05)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Active Customers',
  'Active customer list by type and channel with policy count.',
  'CUSTOMER', 'SYSTEM', 'CUSTOMERS',
  '{
    "fields": [
      {"key":"full_name","label":"Customer","type":"STRING","computed":false},
      {"key":"customer_type","label":"Type","type":"STRING","computed":false},
      {"key":"channel","label":"Channel","type":"STRING","computed":false},
      {"key":"kyc_status","label":"KYC Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Onboarded","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":false},
      {"key":"date_to","label":"Date To","type":"DATE","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"PIE","xAxis":"customer_type","yAxis":"id"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Customer Loss Ratio',
  'Premium vs claims and loss ratio percentage per customer.',
  'CUSTOMER', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"customer_name","label":"Customer","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Claims (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"loss_ratio","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"customer_name","yAxis":"loss_ratio"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Customer Policy History',
  'All policies for a specific customer with class, product, premium, status, and period.',
  'CUSTOMER', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"product_name","label":"Product","type":"STRING","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"start_date","label":"Start","type":"DATE","computed":false},
      {"key":"end_date","label":"End","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":false},
      {"key":"date_to","label":"Date To","type":"DATE","required":false}
    ],
    "sortBy":"start_date","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'KYC Status Report',
  'All customers with KYC verification status and date.',
  'CUSTOMER', 'SYSTEM', 'CUSTOMERS',
  '{
    "fields": [
      {"key":"full_name","label":"Customer","type":"STRING","computed":false},
      {"key":"customer_type","label":"Type","type":"STRING","computed":false},
      {"key":"kyc_status","label":"KYC Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Onboarded","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":false},
      {"key":"date_to","label":"Date To","type":"DATE","required":false}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"PIE","xAxis":"kyc_status","yAxis":"id"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Broker Performance',
  'Policies placed, GWP, claims, loss ratio, and commission earned by broker.',
  'CUSTOMER', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"customer_name","label":"Broker","type":"STRING","computed":false},
      {"key":"premium","label":"GWP (₦)","type":"MONEY","computed":false},
      {"key":"class_of_business","label":"Top Class","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"premium","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"customer_name","yAxis":"premium"}
  }',
  true
);

-- ═══════════════════════════════════════════════════════════════════════════
-- REGULATORY — NAICOM / NIID (8 reports — N01–N08)
-- is_pinnable = FALSE for all regulatory reports
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Annual Revenue Account (NAICOM)',
  'Premium earned, claims incurred, expenses, and profit/loss per class — annual statutory format.',
  'REGULATORY', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "groupBy":"class_of_business","sortBy":"reserve_amount","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"reserve_amount"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Annual Balance Sheet (NAICOM)',
  'Assets, liabilities, and shareholders'' funds — prescribed NAICOM annual format.',
  'REGULATORY', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"Reference","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "sortBy":"amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Quarterly Prudential Return (NAICOM)',
  'Solvency margin, premium reserves, and investment positions — quarterly statutory.',
  'REGULATORY', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"Reference","type":"STRING","computed":false},
      {"key":"amount","label":"Amount (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Quarter Start","type":"DATE","required":true},
      {"key":"date_to","label":"Quarter End","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RI Quarterly Returns (NAICOM)',
  'Ceded premium and claims by treaty and reinsurer — NAICOM quarterly RI return format.',
  'REGULATORY', 'SYSTEM', 'REINSURANCE',
  '{
    "fields": [
      {"key":"treaty_name","label":"Treaty","type":"STRING","computed":false},
      {"key":"ceded_amount","label":"Ceded Premium (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Quarter Start","type":"DATE","required":true},
      {"key":"date_to","label":"Quarter End","type":"DATE","required":true}
    ],
    "sortBy":"ceded_amount","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Premium Bordereaux (NAICOM)',
  'Policy-level premium register in NAICOM prescribed annual format.',
  'REGULATORY', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"sum_insured","label":"Sum Insured (₦)","type":"MONEY","computed":false},
      {"key":"premium","label":"Premium (₦)","type":"MONEY","computed":false},
      {"key":"start_date","label":"Inception","type":"DATE","computed":false},
      {"key":"end_date","label":"Expiry","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "sortBy":"created_at","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Claims Bordereaux (NAICOM)',
  'Claim-level loss register in NAICOM prescribed annual format.',
  'REGULATORY', 'SYSTEM', 'CLAIMS',
  '{
    "fields": [
      {"key":"claim_number","label":"Claim No.","type":"STRING","computed":false},
      {"key":"policy_number","label":"Policy","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"reserve_amount","label":"Reserve (₦)","type":"MONEY","computed":false},
      {"key":"total_paid","label":"Paid (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "sortBy":"registered_at","sortDir":"ASC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'NIID Upload Status Report',
  'Motor and marine policies — NIID upload status, NIID IDs, and failures.',
  'REGULATORY', 'SYSTEM', 'POLICIES',
  '{
    "fields": [
      {"key":"policy_number","label":"Policy No.","type":"STRING","computed":false},
      {"key":"customer_name","label":"Insured","type":"STRING","computed":false},
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"status","label":"Policy Status","type":"STRING","computed":false},
      {"key":"start_date","label":"Inception","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":false},
      {"key":"date_to","label":"Date To","type":"DATE","required":false}
    ],
    "sortBy":"start_date","sortDir":"DESC",
    "chart":{"type":"TABLE_ONLY"}
  }',
  false
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Investment Statement (NAICOM)',
  'All investments by type, value, yield, and percentage of total assets — annual format.',
  'REGULATORY', 'SYSTEM', 'FINANCE',
  '{
    "fields": [
      {"key":"debit_note_number","label":"Reference","type":"STRING","computed":false},
      {"key":"amount","label":"Value (₦)","type":"MONEY","computed":false},
      {"key":"status","label":"Status","type":"STRING","computed":false},
      {"key":"created_at","label":"Date","type":"DATE","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "sortBy":"amount","sortDir":"DESC",
    "chart":{"type":"PIE","xAxis":"debit_note_number","yAxis":"amount"}
  }',
  false
);
