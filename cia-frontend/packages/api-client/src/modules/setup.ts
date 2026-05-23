// ── Setup & Administration — DTOs ─────────────────────────────────────────

// Mirrors com.nubeero.cia.setup.company.dto.CompanySettingsResponse 1:1.
// Earlier shape carried `companyName` (vs backend `name`), `logo` (vs backend
// `logoPath`), and `defaultCurrencyCode` (no backend field at all). The
// rcNumber / naicomLicenseNumber / city / state fields the backend ships
// were missing entirely. Realigned in Session 98 / Backlog A1c.
export interface CompanySettingsDto {
  id:                  string;
  name:                string;
  rcNumber?:           string | null;
  naicomLicenseNumber?: string | null;
  address?:            string | null;
  city?:               string | null;
  state?:              string | null;
  email?:              string | null;
  phone?:              string | null;
  logoPath?:           string | null;
  website?:            string | null;
  createdAt:           string;
  updatedAt:           string;
}

// Mirrors com.nubeero.cia.setup.company.dto.PasswordPolicyResponse 1:1.
// Storage-only — actual login-time enforcement lives in Keycloak's realm
// policy. Backlog F4 (this slice) wires the orphaned V3 entity end-to-end;
// F4-sync would route updates into Keycloak realm settings later. id /
// createdAt / updatedAt are null on the first GET (before any PUT) — the
// service returns V3 DDL defaults so the UI never has to model an empty
// state.
export interface PasswordPolicyDto {
  id?:                string | null;
  minLength:          number;
  maxLength:          number;
  requireUppercase:   boolean;
  requireLowercase:   boolean;
  requireNumbers:     boolean;
  requireSpecial:     boolean;
  expiryDays:         number;
  maxFailedAttempts:  number;
  createdAt?:         string | null;
  updatedAt?:         string | null;
}

export interface UserDto {
  id:              string;
  email:           string;
  firstName:       string;
  lastName:        string;
  status:          'ACTIVE' | 'INACTIVE' | 'LOCKED';
  accessGroupId:   string;
  accessGroupName: string;
  createdAt:       string;
}

// Mirrors com.nubeero.cia.setup.access.dto.AccessGroupResponse 1:1.
// `userCount` was previously declared on the frontend but the backend never
// shipped it — no UI consumer referenced it either, so it's just removed
// rather than promoted to a computed-from-elsewhere field. Audit timestamps
// added in Session 97 / Backlog A1.
export interface AccessGroupDto {
  id:           string;
  name:         string;
  description?: string | null;
  permissions:  string[];
  createdAt:    string;
  updatedAt:    string;
}

// Mirrors com.nubeero.cia.setup.approval.dto.ApprovalGroupResponse 1:1.
// Earlier carried `module` as a UI alias for backend `entityType`, plus a
// per-level shape that modelled multiple approvers with a `minAmount` range
// — neither matches the backend, which models ONE approver per level keyed
// by `levelOrder` + `approverUserId` + `maxAmount`. Realigned in Session 99 /
// Backlog A1b.
export interface ApprovalGroupDto {
  id:        string;
  name:      string;
  entityType: string;
  levels:    ApprovalLevelDto[];
  createdAt: string;
  updatedAt: string;
}

// Mirrors com.nubeero.cia.setup.approval.dto.ApprovalGroupResponse.ApprovalLevelResponse.
// One approver per level (not an array). Backend infers the min-amount band
// for each level from the previous level's maxAmount, so only maxAmount is
// stored per row.
export interface ApprovalLevelDto {
  id:             string;
  levelOrder:     number;
  approverUserId: string;
  approverName:   string;
  maxAmount:      number;
}

// Mirrors com.nubeero.cia.setup.product.dto.ProductSectionResponse 1:1.
// Used for multi-risk products that have multiple coverage sections each
// with their own rate. Surfaced on ProductDto.sections in Session 97 /
// Backlog A1.
export interface ProductSectionDto {
  id:      string;
  name:    string;
  code:    string;
  rate:    number;
  orderNo: number;
}

// Mirrors com.nubeero.cia.setup.product.dto.ProductResponse 1:1.
// Earlier carried `status: ACTIVE|INACTIVE` (backend exposes `active: boolean`)
// and a flat `commissionRate` (commissions live in `commission_setups` keyed by
// CommissionSourceType — never on the Product row). Jackson silently dropped
// both fields on the way in; renderers showed `undefined%` once products
// existed. Aligned in Session 84. `sections` added in Session 97 / Backlog A1.
export interface ProductDto {
  id:                  string;
  name:                string;
  code:                string;
  classOfBusinessId:   string;
  classOfBusinessName: string;
  type:                'SINGLE_RISK' | 'MULTI_RISK';
  rate:                number;
  minPremium:          number;
  active:              boolean;
  sections?:           ProductSectionDto[] | null;
  createdAt:           string;
  updatedAt?:          string | null;
}

// Mirrors com.nubeero.cia.setup.product.dto.ClassOfBusinessResponse 1:1.
// `products` was previously a UI-side product count that the backend never
// shipped; removed because no consumer referenced it. Description + audit
// timestamps added in Session 97 / Backlog A1.
export interface ClassOfBusinessDto {
  id:           string;
  name:         string;
  code:         string;
  description?: string | null;
  createdAt:    string;
  updatedAt:    string;
}

// Mirrors com.nubeero.cia.setup.product.CommissionSourceType (Session 84 / V50).
// AGENT — NAICOM-licensed agent representing the insurer.
// BROKER — NAICOM-licensed broker representing the insured.
// RELATIONSHIP_MANAGER — insurer staff owning the customer relationship.
export type CommissionSourceType = 'AGENT' | 'BROKER' | 'RELATIONSHIP_MANAGER';

// Mirrors com.nubeero.cia.setup.product.dto.CommissionSetupResponse.
// Per-product commission rule keyed by source + date range. See PRD §2.1.17.
export interface CommissionSetupDto {
  id:               string;
  productId:        string;
  commissionSource: CommissionSourceType;
  /** Percent, 0–100 inclusive. */
  rate:             number;
  effectiveFrom:    string;
  effectiveTo?:     string | null;
  createdAt:        string;
  updatedAt?:       string | null;
}

// Mirrors com.nubeero.cia.setup.org.dto.BrokerResponse.
// Previously carried `status` + `contactPerson` which the backend never accepted
// (Jackson silently dropped them on the way in). Now matches the entity 1:1.
// V49 added the optional `licenseNumber` field — NAICOM broker licence.
export interface BrokerDto {
  id:             string;
  name:           string;
  code:           string;
  rcNumber?:      string | null;
  /** NAICOM broker licence number (V49). */
  licenseNumber?: string | null;
  address?:       string | null;
  email?:         string | null;
  phone?:         string | null;
  createdAt:      string;
  updatedAt?:     string | null;
}

// Mirrors com.nubeero.cia.setup.org.dto.BranchResponse.
export interface BranchDto {
  id:         string;
  name:       string;
  code:       string;
  sbuId?:     string | null;
  sbuName?:   string | null;
  address?:   string | null;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.org.dto.SbuResponse.
export interface SbuDto {
  id:         string;
  name:       string;
  code:       string;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.org.dto.ReinsuranceCompanyResponse.
export interface ReinsuranceCompanyDto {
  id:         string;
  name:       string;
  rcNumber?:  string | null;
  address?:   string | null;
  email?:     string | null;
  phone?:     string | null;
  country:    string;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.org.dto.RelationshipManagerResponse.
// V46 added the FK on customers.relationship_manager_id; CustomerService
// denormalises `relationshipManagerName` into customer responses.
export interface RelationshipManagerDto {
  id:         string;
  name:       string;
  email?:     string | null;
  phone?:     string | null;
  branchId?:  string | null;
  branchName?: string | null;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.org.AdjusterType (V45).
export type AdjusterType = 'INTERNAL' | 'EXTERNAL';

// Mirrors com.nubeero.cia.setup.org.dto.AdjusterResponse (V45).
export interface AdjusterDto {
  id:             string;
  name:           string;
  code:           string;
  type:           AdjusterType;
  licenseNumber?: string | null;
  email?:         string | null;
  phone?:         string | null;
  address?:       string | null;
  createdAt:      string;
  updatedAt?:     string | null;
}

// Mirrors com.nubeero.cia.setup.org.AgentType (V48). Agents represent the
// INSURER and earn commission on policies sold, distinct from Brokers
// (who represent the INSURED). Type is the legal form, not engagement model.
export type AgentType = 'INDIVIDUAL' | 'CORPORATE';

// Mirrors com.nubeero.cia.setup.org.dto.AgentResponse (V48).
export interface AgentDto {
  id:             string;
  name:           string;
  code:           string;
  type:           AgentType;
  licenseNumber?: string | null;
  email?:         string | null;
  phone?:         string | null;
  address?:       string | null;
  createdAt:      string;
  updatedAt?:     string | null;
}

// Mirrors com.nubeero.cia.setup.finance.dto.BankResponse 1:1.
// Audit timestamps added in Session 97 / Backlog A1.
export interface BankDto {
  id:        string;
  name:      string;
  code:      string;
  createdAt: string;
  updatedAt: string;
}

// Mirrors com.nubeero.cia.setup.finance.dto.CurrencyResponse 1:1.
// isDefault + audit timestamps added in Session 97 / Backlog A1.
export interface CurrencyDto {
  id:        string;
  name:      string;
  code:      string;
  symbol:    string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

// Used by claims/inspection + policy/survey for the surveyor picker.
// Mirrors com.nubeero.cia.setup.org.dto.SurveyorResponse.
export type SurveyorType = 'INTERNAL' | 'EXTERNAL';

export interface SurveyorDto {
  id:             string;
  name:           string;
  type:           SurveyorType;
  licenseNumber?: string | null;
  email?:         string | null;
  phone?:         string | null;
  createdAt:      string;
  updatedAt?:     string | null;
}

// Used by policy/coinsurance for the participant picker.
// Mirrors com.nubeero.cia.setup.org.dto.InsuranceCompanyResponse.
export interface InsuranceCompanyDto {
  id:             string;
  name:           string;
  rcNumber?:      string | null;
  naicomLicense?: string | null;
  address?:       string | null;
  email?:         string | null;
  phone?:         string | null;
  createdAt:      string;
  updatedAt?:     string | null;
}
