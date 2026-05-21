// ── Customer Onboarding — DTOs ────────────────────────────────────────────

export type CustomerType  = 'INDIVIDUAL' | 'CORPORATE';
export type KycStatus     = 'PENDING' | 'VERIFIED' | 'FAILED' | 'RESUBMIT';
export type CustomerStatus = 'ACTIVE' | 'INACTIVE' | 'BLACKLISTED';

export interface CustomerDto {
  id:                       string;
  customerType:             CustomerType;
  displayName:              string;
  email:                    string;
  phone:                    string;
  kycStatus:                KycStatus;
  status:                   CustomerStatus;
  brokerId?:                string;
  brokerName?:              string;
  /** Internal RelationshipManager owning this customer's portfolio.
   *  Backed by `customers.relationship_manager_id` (V46). Required at
   *  the onboarding UI; nullable on the entity for pre-V46 records. */
  relationshipManagerId?:   string;
  relationshipManagerName?: string;
  createdAt:                string;
  updatedAt:                string;
}

export interface IndividualCustomerDto extends CustomerDto {
  firstName:      string;
  lastName:       string;
  dateOfBirth:    string;
  idType:         'NIN' | 'VOTERS_CARD' | 'DRIVERS_LICENSE' | 'PASSPORT';
  idNumber:       string;
  address:        string;
  occupation?:    string;
}

export interface CorporateCustomerDto extends CustomerDto {
  companyName:    string;
  rcNumber:       string;
  address:        string;
  directors:      CustomerDirectorDto[];
}

export interface CustomerDirectorDto {
  id:          string;
  fullName:    string;
  idType:      string;
  idNumber:    string;
  kycStatus:   KycStatus;
}
