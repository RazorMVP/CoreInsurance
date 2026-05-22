// ── Setup & Administration — DTOs ─────────────────────────────────────────

export interface CompanySettingsDto {
  id:               string;
  companyName:      string;
  logo?:            string;
  address:          string;
  email:            string;
  phone:            string;
  website?:         string;
  defaultCurrencyCode: string;
  createdAt:        string;
  updatedAt:        string;
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

export interface ApprovalGroupDto {
  id:          string;
  name:        string;
  module:      string;
  levels:      ApprovalLevelDto[];
}

export interface ApprovalLevelDto {
  level:         number;
  minAmount:     number;
  maxAmount:     number;
  approverIds:   string[];
  approverNames: string[];
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
