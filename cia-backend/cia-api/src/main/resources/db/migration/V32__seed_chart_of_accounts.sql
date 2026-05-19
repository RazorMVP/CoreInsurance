-- ─────────────────────────────────────────────────────────────────────────────
-- V32 — Seed Chart of Accounts
--
-- Module 12 (Period-End Closures) Slice 1.2 — pure data, no DDL.
-- Seeds the 3-level Nigerian general-insurance COA against the V31 schema:
--   Level 1 (Classes):  5 rows   — codes 1000 / 2000 / 3000 / 4000 / 5000
--   Level 2 (Groups):   27 rows  — non-postable parents
--   Level 3 (Leaves):   97 rows  — postable accounts (target of every posting_rule FK)
--   Total:              129 rows
--
-- IFRS 17 / IFRS 9 role tags are populated on leaves where measurement modules
-- (slices 2.x and 3.x) need to look up specific posting targets. All other
-- accounts leave the role columns NULL.
--
-- Idempotency: every INSERT uses ON CONFLICT (code) DO NOTHING so the seed is
-- safe to re-run during data-migration cleanups. Behaviour is locked by the
-- expected-tree.txt fixture and the V32 seed test in cia-finance.
--
-- Scope decisions (locked Session 56):
--   R1=A  inward FAC liabilities (2210, 2220) seeded now — Module 6 supports
--         inward FAC end-to-end, the first approval would otherwise fail the
--         posting_rule.debit_account FK lookup.
--   R2=A  insurance finance OCI account (3430) seeded unconditionally — OCI
--         election is a tenant config decision, not a COA decision; the leaf
--         sits at zero until elected.
--   R3=A  no separate DAC asset under IFRS 17 PAA — acquisition cost cash flow
--         already routes through 4120 REVENUE_ACQ_RECOVERY and 5130 ACQ_EXPENSE.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Level 1: Classes ─────────────────────────────────────────────────────────
INSERT INTO chart_of_account (code, name, account_type, parent_id, ifrs17_role, ifrs9_role, created_by)
VALUES
    ('1000', 'Assets',      'ASSET',     NULL, NULL, NULL, 'system-seed'),
    ('2000', 'Liabilities', 'LIABILITY', NULL, NULL, NULL, 'system-seed'),
    ('3000', 'Equity',      'EQUITY',    NULL, NULL, NULL, 'system-seed'),
    ('4000', 'Income',      'INCOME',    NULL, NULL, NULL, 'system-seed'),
    ('5000', 'Expenses',    'EXPENSE',   NULL, NULL, NULL, 'system-seed')
ON CONFLICT (code) DO NOTHING;

-- ── Level 2: Groups ──────────────────────────────────────────────────────────
INSERT INTO chart_of_account (code, name, account_type, parent_id, ifrs17_role, ifrs9_role, created_by)
SELECT v.code, v.name, v.account_type, p.id, NULL, NULL, 'system-seed'
FROM (VALUES
    -- Assets (7)
    ('1100', 'Cash and cash equivalents',                'ASSET',     '1000'),
    ('1200', 'Investments',                              'ASSET',     '1000'),
    ('1300', 'Insurance receivables',                    'ASSET',     '1000'),
    ('1400', 'Reinsurance contract held',                'ASSET',     '1000'),
    ('1500', 'Other assets',                             'ASSET',     '1000'),
    ('1600', 'Property, plant and equipment',            'ASSET',     '1000'),
    ('1700', 'Intangible assets',                        'ASSET',     '1000'),
    -- Liabilities (6)
    ('2100', 'Insurance contract liabilities (issued)',  'LIABILITY', '2000'),
    ('2200', 'Reinsurance contracts issued (inward FAC)','LIABILITY', '2000'),
    ('2300', 'Trade payables',                           'LIABILITY', '2000'),
    ('2400', 'Tax liabilities',                          'LIABILITY', '2000'),
    ('2500', 'Other liabilities',                        'LIABILITY', '2000'),
    ('2600', 'Borrowings',                               'LIABILITY', '2000'),
    -- Equity (4)
    ('3100', 'Share capital',                            'EQUITY',    '3000'),
    ('3200', 'Retained earnings',                        'EQUITY',    '3000'),
    ('3300', 'Statutory reserves',                       'EQUITY',    '3000'),
    ('3400', 'OCI reserves',                             'EQUITY',    '3000'),
    -- Income (4)
    ('4100', 'Insurance revenue',                        'INCOME',    '4000'),
    ('4200', 'Investment income',                        'INCOME',    '4000'),
    ('4300', 'Reinsurance income (ceded)',               'INCOME',    '4000'),
    ('4400', 'Other income',                             'INCOME',    '4000'),
    -- Expenses (6)
    ('5100', 'Insurance service expense',                'EXPENSE',   '5000'),
    ('5200', 'Reinsurance expense (outward)',            'EXPENSE',   '5000'),
    ('5300', 'Investment expense',                       'EXPENSE',   '5000'),
    ('5400', 'Operating expense',                        'EXPENSE',   '5000'),
    ('5500', 'Finance costs',                            'EXPENSE',   '5000'),
    ('5600', 'Tax expense',                              'EXPENSE',   '5000')
) AS v(code, name, account_type, parent_code)
JOIN chart_of_account p ON p.code = v.parent_code
ON CONFLICT (code) DO NOTHING;

-- ── Level 3: Leaves ──────────────────────────────────────────────────────────
INSERT INTO chart_of_account (code, name, account_type, parent_id, ifrs17_role, ifrs9_role, created_by)
SELECT v.code, v.name, v.account_type, p.id, v.ifrs17_role, v.ifrs9_role, 'system-seed'
FROM (VALUES
    -- 1100 Cash and cash equivalents (4)
    ('1110', 'Cash on hand',                                    'ASSET',     '1100', NULL,                          NULL),
    ('1120', 'Bank current accounts',                           'ASSET',     '1100', NULL,                          NULL),
    ('1130', 'Bank call deposits',                              'ASSET',     '1100', NULL,                          NULL),
    ('1140', 'Money market instruments',                        'ASSET',     '1100', NULL,                          NULL),
    -- 1200 Investments (5)
    ('1210', 'FVPL - Equity securities',                        'ASSET',     '1200', NULL,                          'FVPL'),
    ('1220', 'FVPL - Debt securities',                          'ASSET',     '1200', NULL,                          'FVPL'),
    ('1230', 'FVOCI - Debt securities',                         'ASSET',     '1200', NULL,                          'FVOCI_DEBT'),
    ('1240', 'FVOCI - Equity securities (elected)',             'ASSET',     '1200', NULL,                          'FVOCI_EQUITY'),
    ('1250', 'Amortised cost - Debt securities',                'ASSET',     '1200', NULL,                          'AMORTISED_COST'),
    -- 1300 Insurance receivables (4)
    ('1310', 'Premium receivable - Direct',                     'ASSET',     '1300', NULL,                          NULL),
    ('1320', 'Premium receivable - Broker',                     'ASSET',     '1300', NULL,                          NULL),
    ('1330', 'Premium receivable - Coinsurer (inward)',         'ASSET',     '1300', NULL,                          NULL),
    ('1340', 'ECL allowance - Premium receivable',              'ASSET',     '1300', NULL,                          'ECL_ALLOWANCE'),
    -- 1400 Reinsurance contract held (4)
    ('1410', 'Reinsurance - LRC asset',                         'ASSET',     '1400', 'LRC_REINSURANCE',             NULL),
    ('1420', 'Reinsurance - LIC asset',                         'ASSET',     '1400', 'LIC_REINSURANCE',             NULL),
    ('1430', 'Reinsurance recoveries receivable',               'ASSET',     '1400', NULL,                          NULL),
    ('1440', 'ECL allowance - Reinsurance recoveries',          'ASSET',     '1400', NULL,                          'ECL_ALLOWANCE'),
    -- 1500 Other assets (3)
    ('1510', 'Prepayments',                                     'ASSET',     '1500', NULL,                          NULL),
    ('1520', 'Other receivables',                               'ASSET',     '1500', NULL,                          NULL),
    ('1530', 'Tax recoverable',                                 'ASSET',     '1500', NULL,                          NULL),
    -- 1600 Property, plant and equipment (5)
    ('1610', 'Land and buildings',                              'ASSET',     '1600', NULL,                          NULL),
    ('1620', 'Motor vehicles',                                  'ASSET',     '1600', NULL,                          NULL),
    ('1630', 'Computer equipment',                              'ASSET',     '1600', NULL,                          NULL),
    ('1640', 'Furniture and fittings',                          'ASSET',     '1600', NULL,                          NULL),
    ('1650', 'Accumulated depreciation (contra)',               'ASSET',     '1600', NULL,                          NULL),
    -- 1700 Intangible assets (2)
    ('1710', 'Software',                                        'ASSET',     '1700', NULL,                          NULL),
    ('1720', 'Accumulated amortisation (contra)',               'ASSET',     '1700', NULL,                          NULL),
    -- 2100 Insurance contract liabilities (issued) (7)
    ('2110', 'LRC - Best estimate of liabilities',              'LIABILITY', '2100', 'LRC_BEL',                     NULL),
    ('2120', 'LRC - Risk adjustment',                           'LIABILITY', '2100', 'LRC_RA',                      NULL),
    ('2130', 'LRC - Loss component (onerous)',                  'LIABILITY', '2100', 'LRC_LC',                      NULL),
    ('2140', 'LIC - Outstanding claims reserve',                'LIABILITY', '2100', 'LIC_OCR',                     NULL),
    ('2150', 'LIC - IBNR',                                      'LIABILITY', '2100', 'LIC_IBNR',                    NULL),
    ('2160', 'LIC - Risk adjustment',                           'LIABILITY', '2100', 'LIC_RA',                      NULL),
    ('2170', 'LIC - Claims handling expense provision',         'LIABILITY', '2100', 'LIC_CHE',                     NULL),
    -- 2200 Reinsurance contracts issued (inward FAC) (2)
    ('2210', 'Inward reinsurance - LRC',                        'LIABILITY', '2200', 'LRC_BEL',                     NULL),
    ('2220', 'Inward reinsurance - LIC',                        'LIABILITY', '2200', 'LIC_OCR',                     NULL),
    -- 2300 Trade payables (5)
    ('2310', 'Reinsurance premium payable (outward)',           'LIABILITY', '2300', NULL,                          NULL),
    ('2320', 'Commission payable - Brokers',                    'LIABILITY', '2300', NULL,                          NULL),
    ('2330', 'Commission payable - Agents',                     'LIABILITY', '2300', NULL,                          NULL),
    ('2340', 'Coinsurance payable (outward share)',             'LIABILITY', '2300', NULL,                          NULL),
    ('2350', 'Claims payable',                                  'LIABILITY', '2300', NULL,                          NULL),
    -- 2400 Tax liabilities (4)
    ('2410', 'Income tax payable',                              'LIABILITY', '2400', NULL,                          NULL),
    ('2420', 'VAT payable',                                     'LIABILITY', '2400', NULL,                          NULL),
    ('2430', 'WHT payable',                                     'LIABILITY', '2400', NULL,                          NULL),
    ('2440', 'NAICOM levy payable',                             'LIABILITY', '2400', NULL,                          NULL),
    -- 2500 Other liabilities (3)
    ('2510', 'Accruals',                                        'LIABILITY', '2500', NULL,                          NULL),
    ('2520', 'Staff payables',                                  'LIABILITY', '2500', NULL,                          NULL),
    ('2530', 'Other creditors',                                 'LIABILITY', '2500', NULL,                          NULL),
    -- 2600 Borrowings (2)
    ('2610', 'Bank borrowings',                                 'LIABILITY', '2600', NULL,                          NULL),
    ('2620', 'Lease liabilities',                               'LIABILITY', '2600', NULL,                          NULL),
    -- 3100 Share capital (2)
    ('3110', 'Ordinary share capital',                          'EQUITY',    '3100', NULL,                          NULL),
    ('3120', 'Share premium',                                   'EQUITY',    '3100', NULL,                          NULL),
    -- 3200 Retained earnings (2)
    ('3210', 'Retained earnings - Brought forward',             'EQUITY',    '3200', NULL,                          NULL),
    ('3220', 'Profit / loss - Current period',                  'EQUITY',    '3200', NULL,                          NULL),
    -- 3300 Statutory reserves (2)
    ('3310', 'Contingency reserve (NAICOM mandatory)',          'EQUITY',    '3300', NULL,                          NULL),
    ('3320', 'Statutory reserve',                               'EQUITY',    '3300', NULL,                          NULL),
    -- 3400 OCI reserves (3)
    ('3410', 'FVOCI debt reserve',                              'EQUITY',    '3400', NULL,                          'OCI_DEBT_RESERVE'),
    ('3420', 'FVOCI equity reserve',                            'EQUITY',    '3400', NULL,                          'OCI_EQUITY_RESERVE'),
    ('3430', 'Insurance finance OCI (if elected)',              'EQUITY',    '3400', 'INSURANCE_FINANCE_OCI',       NULL),
    -- 4100 Insurance revenue (4)
    ('4110', 'Insurance revenue - LRC release',                 'INCOME',    '4100', 'REVENUE_LRC_RELEASE',         NULL),
    ('4120', 'Insurance revenue - Acquisition cost recovery',   'INCOME',    '4100', 'REVENUE_ACQ_RECOVERY',        NULL),
    ('4130', 'Change in risk adjustment (non-financial)',       'INCOME',    '4100', 'REVENUE_RA_RELEASE',          NULL),
    ('4140', 'Experience adjustment',                           'INCOME',    '4100', 'REVENUE_EXP_ADJ',             NULL),
    -- 4200 Investment income (6)
    ('4210', 'Interest income - Amortised cost',                'INCOME',    '4200', NULL,                          'INTEREST_AC'),
    ('4220', 'Interest income - FVOCI debt',                    'INCOME',    '4200', NULL,                          'INTEREST_FVOCI'),
    ('4230', 'Dividend income',                                 'INCOME',    '4200', NULL,                          NULL),
    ('4240', 'Realised gains - Investments',                    'INCOME',    '4200', NULL,                          NULL),
    ('4250', 'Unrealised FV gains - FVPL',                      'INCOME',    '4200', NULL,                          'FVPL_GAINS'),
    ('4260', 'FX gains',                                        'INCOME',    '4200', NULL,                          NULL),
    -- 4300 Reinsurance income (ceded) (2)
    ('4310', 'Amounts recoverable for incurred claims',         'INCOME',    '4300', 'REINSURANCE_RECOVERY',        NULL),
    ('4320', 'Reinsurance commission income',                   'INCOME',    '4300', NULL,                          NULL),
    -- 4400 Other income (2)
    ('4410', 'Sundry income',                                   'INCOME',    '4400', NULL,                          NULL),
    ('4420', 'Fee income',                                      'INCOME',    '4400', NULL,                          NULL),
    -- 5100 Insurance service expense (5)
    ('5110', 'Incurred claims',                                 'EXPENSE',   '5100', 'INCURRED_CLAIMS',             NULL),
    ('5120', 'Change in LIC',                                   'EXPENSE',   '5100', 'LIC_CHANGE',                  NULL),
    ('5130', 'Insurance acquisition expense',                   'EXPENSE',   '5100', 'ACQ_EXPENSE',                 NULL),
    ('5140', 'Other directly attributable expenses',            'EXPENSE',   '5100', 'OTHER_DIRECT_EXPENSE',        NULL),
    ('5150', 'Loss component change',                           'EXPENSE',   '5100', 'LC_CHANGE',                   NULL),
    -- 5200 Reinsurance expense (outward) (3)
    ('5210', 'Outward reinsurance premium',                     'EXPENSE',   '5200', 'REINSURANCE_PREMIUM',         NULL),
    ('5220', 'Change in reinsurance LRC asset',                 'EXPENSE',   '5200', 'REINSURANCE_LRC_CHANGE',      NULL),
    ('5230', 'Reinsurance commission expense',                  'EXPENSE',   '5200', NULL,                          NULL),
    -- 5300 Investment expense (6)
    ('5310', 'Investment management fees',                      'EXPENSE',   '5300', NULL,                          NULL),
    ('5320', 'Realised losses - Investments',                   'EXPENSE',   '5300', NULL,                          NULL),
    ('5330', 'Unrealised FV losses - FVPL',                     'EXPENSE',   '5300', NULL,                          'FVPL_LOSSES'),
    ('5340', 'ECL expense - Investment securities',             'EXPENSE',   '5300', NULL,                          'ECL_EXPENSE'),
    ('5350', 'ECL expense - Premium receivables',               'EXPENSE',   '5300', NULL,                          'ECL_EXPENSE'),
    ('5360', 'FX losses',                                       'EXPENSE',   '5300', NULL,                          NULL),
    -- 5400 Operating expense (6)
    ('5410', 'Staff costs',                                     'EXPENSE',   '5400', NULL,                          NULL),
    ('5420', 'Depreciation',                                    'EXPENSE',   '5400', NULL,                          NULL),
    ('5430', 'Amortisation',                                    'EXPENSE',   '5400', NULL,                          NULL),
    ('5440', 'Office and administration',                       'EXPENSE',   '5400', NULL,                          NULL),
    ('5450', 'Professional fees',                               'EXPENSE',   '5400', NULL,                          NULL),
    ('5460', 'Marketing',                                       'EXPENSE',   '5400', NULL,                          NULL),
    -- 5500 Finance costs (2)
    ('5510', 'Interest expense',                                'EXPENSE',   '5500', NULL,                          NULL),
    ('5520', 'Insurance finance expense',                       'EXPENSE',   '5500', 'INSURANCE_FINANCE_EXPENSE',   NULL),
    -- 5600 Tax expense (2)
    ('5610', 'Current tax',                                     'EXPENSE',   '5600', NULL,                          NULL),
    ('5620', 'Deferred tax',                                    'EXPENSE',   '5600', NULL,                          NULL)
) AS v(code, name, account_type, parent_code, ifrs17_role, ifrs9_role)
JOIN chart_of_account p ON p.code = v.parent_code
ON CONFLICT (code) DO NOTHING;
