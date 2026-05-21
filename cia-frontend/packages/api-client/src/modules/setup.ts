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

export interface AccessGroupDto {
  id:          string;
  name:        string;
  description?: string;
  permissions: string[];
  userCount:   number;
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

export interface ProductDto {
  id:               string;
  name:             string;
  code:             string;
  classOfBusinessId: string;
  classOfBusinessName: string;
  type:             'SINGLE_RISK' | 'MULTI_RISK';
  status:           'ACTIVE' | 'INACTIVE';
  commissionRate:   number;
  createdAt:        string;
}

export interface ClassOfBusinessDto {
  id:       string;
  name:     string;
  code:     string;
  products: number;
}

// Mirrors com.nubeero.cia.setup.org.dto.BrokerResponse.
// Previously carried `status` + `contactPerson` which the backend never accepted
// (Jackson silently dropped them on the way in). Now matches the entity 1:1.
export interface BrokerDto {
  id:         string;
  name:       string;
  code:       string;
  rcNumber?:  string | null;
  address?:   string | null;
  email?:     string | null;
  phone?:     string | null;
  createdAt:  string;
  updatedAt?: string | null;
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

export interface BankDto {
  id:   string;
  name: string;
  code: string;
}

export interface CurrencyDto {
  id:     string;
  name:   string;
  code:   string;
  symbol: string;
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
