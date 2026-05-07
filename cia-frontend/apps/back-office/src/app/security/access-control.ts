export const ACCESS = {
  dashboard: [],
  setupModule: ['ROLE_SETUP_VIEW', 'setup:view'],
  setupView: ['ROLE_SETUP_VIEW'],
  customerView: ['ROLE_CUSTOMER_VIEW'],
  quotationView: ['ROLE_QUOTATION_VIEW'],
  underwritingView: ['ROLE_UNDERWRITING_VIEW'],
  claimsView: ['ROLE_CLAIMS_VIEW'],
  reinsuranceView: ['ROLE_REINSURANCE_VIEW'],
  financeView: ['ROLE_FINANCE_VIEW'],
  reportsView: ['reports:view'],
  auditView: ['ROLE_AUDIT_VIEW', 'ROLE_SETUP_UPDATE'],
} as const;

export type AccessRule = readonly string[];

export const MODULE_ACCESS = {
  dashboard: ACCESS.dashboard,
  setup: ACCESS.setupModule,
  customers: ACCESS.customerView,
  quotation: ACCESS.quotationView,
  policies: ACCESS.underwritingView,
  endorsements: ACCESS.underwritingView,
  claims: ACCESS.claimsView,
  reinsurance: ACCESS.reinsuranceView,
  finance: ACCESS.financeView,
  reports: ACCESS.reportsView,
  audit: ACCESS.auditView,
} as const satisfies Record<string, AccessRule>;

export const SETUP_ROUTE_ACCESS = {
  '/setup/company': ACCESS.setupView,
  '/setup/users': ACCESS.setupView,
  '/setup/access-groups': ACCESS.setupView,
  '/setup/approval-groups': ACCESS.setupView,
  '/setup/classes': ACCESS.setupView,
  '/setup/products': ACCESS.setupView,
  '/setup/policy-specifications': ACCESS.setupView,
  '/setup/organisations': ACCESS.setupView,
  '/setup/vehicle-registry': ACCESS.setupView,
  '/setup/claims-config': ACCESS.setupView,
  '/setup/partner-apps': ['setup:view'],
  '/setup/customer-number-format': ACCESS.setupView,
} as const satisfies Record<string, AccessRule>;
