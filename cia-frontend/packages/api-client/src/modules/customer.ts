// ── Customer Onboarding — DTOs ────────────────────────────────────────────
//
// Session 94 / Backlog A2: rewrote CustomerDto + CustomerDirectorDto to mirror
// the backend response shape 1:1, removing the silent-drop projections
// (`displayName`, `status` aliasing customerStatus, `brokerId`/`brokerName`
// that the backend never returns) and adding the full KYC + address +
// type-discriminated fields the backend actually serialises.
//
// `customerLabel(customer)` is the canonical UI-side helper for displaying a
// customer's name — replaces the old top-level `displayName` field that
// Jackson silently dropped.

export type CustomerType   = 'INDIVIDUAL' | 'CORPORATE';
export type KycStatus      = 'PENDING' | 'VERIFIED' | 'FAILED' | 'RESUBMIT';
export type CustomerStatus = 'ACTIVE' | 'INACTIVE' | 'BLACKLISTED';
export type IdType         = 'NIN' | 'VOTERS_CARD' | 'DRIVERS_LICENSE' | 'PASSPORT';

// Mirrors com.nubeero.cia.customer.dto.CustomerResponse 1:1.
// Type-discriminated: individual fields are non-null when customerType is
// INDIVIDUAL, corporate fields when CORPORATE. Common fields (email, phone,
// address...) populate for both types. relationshipManagerId/Name come from
// V46.
export interface CustomerDto {
  id:                       string;
  customerNumber:           string;
  customerType:             CustomerType;
  customerStatus:           CustomerStatus;
  kycStatus:                KycStatus;
  kycProviderRef?:          string | null;
  kycFailureReason?:        string | null;
  kycVerifiedAt?:           string | null;

  // Individual fields (populated when customerType === 'INDIVIDUAL')
  firstName?:               string | null;
  lastName?:                string | null;
  otherNames?:              string | null;
  dateOfBirth?:             string | null;
  gender?:                  string | null;
  maritalStatus?:           string | null;
  idType?:                  IdType | null;
  idNumber?:                string | null;
  idDocumentUrl?:           string | null;
  idExpiryDate?:            string | null;

  // Corporate fields (populated when customerType === 'CORPORATE')
  companyName?:             string | null;
  rcNumber?:                string | null;
  cacCertificateUrl?:       string | null;
  cacIssuedDate?:           string | null;
  incorporationDate?:       string | null;
  industry?:                string | null;
  contactPerson?:           string | null;

  // Common
  email:                    string;
  phone:                    string;
  alternatePhone?:          string | null;
  address?:                 string | null;
  city?:                    string | null;
  state?:                   string | null;
  country?:                 string | null;

  // V46 — Relationship Manager attribution
  relationshipManagerId?:   string | null;
  relationshipManagerName?: string | null;

  directors?:               CustomerDirectorDto[] | null;
  documents?:               CustomerDocumentDto[] | null;

  createdAt:                string;
  updatedAt:                string;
}

// Mirrors com.nubeero.cia.customer.dto.CustomerDirectorResponse 1:1.
// Backend stores firstName + lastName separately. The previous frontend
// `fullName` field was a Jackson silent-drop on response and a wrong field
// name in the multipart corporate-onboarding submission.
export interface CustomerDirectorDto {
  id:                string;
  firstName:         string;
  lastName:          string;
  dateOfBirth?:      string | null;
  idType?:           IdType | null;
  idNumber?:         string | null;
  idDocumentUrl?:    string | null;
  idExpiryDate?:     string | null;
  kycStatus:         KycStatus;
  kycFailureReason?: string | null;
}

// Mirrors com.nubeero.cia.customer.dto.CustomerDocumentResponse 1:1.
export interface CustomerDocumentDto {
  id:             string;
  documentType:   string;
  documentName:   string;
  documentPath:   string;
  mimeType:       string;
  fileSizeBytes:  number;
  uploadedBy?:    string | null;
  createdAt:      string;
}

// UI helper — produce a human-readable label from a CustomerDto.
// Used by list / detail / picker components in place of the old top-level
// `displayName` field that the backend never serialised.
export function customerLabel(c: Pick<CustomerDto, 'customerType' | 'firstName' | 'lastName' | 'companyName'>): string {
  if (c.customerType === 'CORPORATE') return c.companyName ?? '(unnamed corporate)';
  return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || '(unnamed individual)';
}
