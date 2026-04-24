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

export interface BrokerDto {
  id:           string;
  name:         string;
  code:         string;
  email:        string;
  phone:        string;
  status:       'ACTIVE' | 'INACTIVE';
  contactPerson: string;
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
