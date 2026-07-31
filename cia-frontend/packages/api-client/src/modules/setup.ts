// ── Setup & Administration — DTOs + Notification Template schemas/fetchers ─

import { z } from 'zod';
import { apiClient } from '../client';
import { validatedGet, validatedPost, validatedPut } from '../validation';

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

export const UserDtoSchema = z.object({
  id:              z.string(),
  email:           z.string(),
  firstName:       z.string(),
  lastName:        z.string(),
  status:          z.enum(['ACTIVE', 'INACTIVE', 'LOCKED']),
  accessGroupId:   z.string(),
  accessGroupName: z.string(),
  createdAt:       z.string(),
});
export type UserDto = z.infer<typeof UserDtoSchema>;

// Mirrors com.nubeero.cia.setup.access.dto.AccessGroupResponse 1:1.
// `userCount` was previously declared on the frontend but the backend never
// shipped it — no UI consumer referenced it either, so it's just removed
// rather than promoted to a computed-from-elsewhere field. Audit timestamps
// added in Session 97 / Backlog A1.
export const AccessGroupDtoSchema = z.object({
  id:          z.string(),
  name:        z.string(),
  description: z.string().nullable().optional(),
  permissions: z.array(z.string()),
  createdAt:   z.string(),
  updatedAt:   z.string(),
});
export type AccessGroupDto = z.infer<typeof AccessGroupDtoSchema>;

// Mirrors com.nubeero.cia.setup.approval.dto.ApprovalGroupResponse.ApprovalLevelResponse.
// One approver per level (not an array). Backend infers the min-amount band
// for each level from the previous level's maxAmount, so only maxAmount is
// stored per row. Declared before ApprovalGroupDtoSchema so the const
// reference below is initialised (no temporal-dead-zone throw at import).
export const ApprovalLevelDtoSchema = z.object({
  id:             z.string(),
  levelOrder:     z.number(),
  approverUserId: z.string(),
  approverName:   z.string(),
  maxAmount:      z.number(),
});
export type ApprovalLevelDto = z.infer<typeof ApprovalLevelDtoSchema>;

// Mirrors com.nubeero.cia.setup.approval.dto.ApprovalGroupResponse 1:1.
// Earlier carried `module` as a UI alias for backend `entityType`, plus a
// per-level shape that modelled multiple approvers with a `minAmount` range
// — neither matches the backend, which models ONE approver per level keyed
// by `levelOrder` + `approverUserId` + `maxAmount`. Realigned in Session 99 /
// Backlog A1b.
export const ApprovalGroupDtoSchema = z.object({
  id:         z.string(),
  name:       z.string(),
  entityType: z.string(),
  levels:     z.array(ApprovalLevelDtoSchema),
  createdAt:  z.string(),
  updatedAt:  z.string(),
});
export type ApprovalGroupDto = z.infer<typeof ApprovalGroupDtoSchema>;

// Mirrors com.nubeero.cia.setup.product.dto.ProductSectionResponse 1:1.
// Used for multi-risk products that have multiple coverage sections each
// with their own rate. Surfaced on ProductDto.sections in Session 97 /
// Backlog A1.
export const ProductSectionDtoSchema = z.object({
  id:      z.string(),
  name:    z.string(),
  code:    z.string(),
  rate:    z.number(),
  orderNo: z.number(),
});
export type ProductSectionDto = z.infer<typeof ProductSectionDtoSchema>;

// Mirrors com.nubeero.cia.setup.product.dto.ProductResponse 1:1.
// Earlier carried `status: ACTIVE|INACTIVE` (backend exposes `active: boolean`)
// and a flat `commissionRate` (commissions live in `commission_setups` keyed by
// CommissionSourceType — never on the Product row). Jackson silently dropped
// both fields on the way in; renderers showed `undefined%` once products
// existed. Aligned in Session 84. `sections` added in Session 97 / Backlog A1.
export const ProductDtoSchema = z.object({
  id:                  z.string(),
  name:                z.string(),
  code:                z.string(),
  classOfBusinessId:   z.string(),
  classOfBusinessName: z.string(),
  type:                z.enum(['SINGLE_RISK', 'MULTI_RISK']),
  rate:                z.number(),
  minPremium:          z.number(),
  active:              z.boolean(),
  sections:            z.array(ProductSectionDtoSchema).nullable().optional(),
  createdAt:           z.string(),
  updatedAt:           z.string().nullable().optional(),
});
export type ProductDto = z.infer<typeof ProductDtoSchema>;

// Mirrors com.nubeero.cia.setup.product.dto.ClassOfBusinessResponse 1:1.
// `products` was previously a UI-side product count that the backend never
// shipped; removed because no consumer referenced it. Description + audit
// timestamps added in Session 97 / Backlog A1.
export const ClassOfBusinessDtoSchema = z.object({
  id:          z.string(),
  name:        z.string(),
  code:        z.string(),
  description: z.string().nullable().optional(),
  createdAt:   z.string(),
  updatedAt:   z.string(),
});
export type ClassOfBusinessDto = z.infer<typeof ClassOfBusinessDtoSchema>;

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
export const BrokerDtoSchema = z.object({
  id:            z.string(),
  name:          z.string(),
  code:          z.string(),
  rcNumber:      z.string().nullable().optional(),
  // NAICOM broker licence number (V49).
  licenseNumber: z.string().nullable().optional(),
  address:       z.string().nullable().optional(),
  email:         z.string().nullable().optional(),
  phone:         z.string().nullable().optional(),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type BrokerDto = z.infer<typeof BrokerDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.dto.BranchResponse.
export const BranchDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  code:      z.string(),
  sbuId:     z.string().nullable().optional(),
  sbuName:   z.string().nullable().optional(),
  address:   z.string().nullable().optional(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type BranchDto = z.infer<typeof BranchDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.dto.SbuResponse.
export const SbuDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  code:      z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type SbuDto = z.infer<typeof SbuDtoSchema>;

// Mirrors com.nubeero.cia.setup.vehicle.dto.VehicleMakeResponse.
export const VehicleMakeDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type VehicleMakeDto = z.infer<typeof VehicleMakeDtoSchema>;

// Mirrors com.nubeero.cia.setup.vehicle.dto.VehicleTypeResponse.
export const VehicleTypeDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type VehicleTypeDto = z.infer<typeof VehicleTypeDtoSchema>;

// Mirrors com.nubeero.cia.setup.vehicle.dto.VehicleModelResponse.
// Nested under a make: makeId + denormalised makeName.
export const VehicleModelDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  makeId:    z.string(),
  makeName:  z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type VehicleModelDto = z.infer<typeof VehicleModelDtoSchema>;

// Mirrors com.nubeero.cia.setup.loss.dto.ClaimReserveCategoryResponse.
export const ClaimReserveCategoryDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  code:      z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type ClaimReserveCategoryDto = z.infer<typeof ClaimReserveCategoryDtoSchema>;

// Mirrors com.nubeero.cia.setup.loss.dto.NatureOfLossResponse.
export const NatureOfLossDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  code:      z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type NatureOfLossDto = z.infer<typeof NatureOfLossDtoSchema>;

// Mirrors com.nubeero.cia.setup.loss.dto.CauseOfLossResponse. FK-linked to a nature.
export const CauseOfLossDtoSchema = z.object({
  id:               z.string(),
  name:             z.string(),
  code:             z.string(),
  natureOfLossId:   z.string(),
  natureOfLossName: z.string(),
  createdAt:        z.string(),
  updatedAt:        z.string().nullable().optional(),
});
export type CauseOfLossDto = z.infer<typeof CauseOfLossDtoSchema>;

// Mirrors com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineResponse (per-product singleton).
export interface ClaimNotificationTimelineDto {
  id:               string;
  productId:        string;
  notificationDays: number;
  createdAt:        string;
  updatedAt?:       string | null;
}

// Mirrors com.nubeero.cia.setup.product.dto.ClaimDocumentRequirementResponse (per-product list row).
export const ClaimDocumentRequirementDtoSchema = z.object({
  id:           z.string(),
  productId:    z.string(),
  documentName: z.string(),
  mandatory:    z.boolean(),
  documentType: z.string(),
  createdAt:    z.string(),
  updatedAt:    z.string().nullable().optional(),
});
export type ClaimDocumentRequirementDto = z.infer<typeof ClaimDocumentRequirementDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.dto.ReinsuranceCompanyResponse.
export const ReinsuranceCompanyDtoSchema = z.object({
  id:        z.string(),
  name:      z.string(),
  rcNumber:  z.string().nullable().optional(),
  address:   z.string().nullable().optional(),
  email:     z.string().nullable().optional(),
  phone:     z.string().nullable().optional(),
  country:   z.string(),
  createdAt: z.string(),
  updatedAt: z.string().nullable().optional(),
});
export type ReinsuranceCompanyDto = z.infer<typeof ReinsuranceCompanyDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.dto.RelationshipManagerResponse.
// V46 added the FK on customers.relationship_manager_id; CustomerService
// denormalises `relationshipManagerName` into customer responses.
export const RelationshipManagerDtoSchema = z.object({
  id:         z.string(),
  name:       z.string(),
  email:      z.string().nullable().optional(),
  phone:      z.string().nullable().optional(),
  branchId:   z.string().nullable().optional(),
  branchName: z.string().nullable().optional(),
  createdAt:  z.string(),
  updatedAt:  z.string().nullable().optional(),
});
export type RelationshipManagerDto = z.infer<typeof RelationshipManagerDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.AdjusterType (V45).
export type AdjusterType = 'INTERNAL' | 'EXTERNAL';

// Mirrors com.nubeero.cia.setup.org.dto.AdjusterResponse (V45).
export const AdjusterDtoSchema = z.object({
  id:            z.string(),
  name:          z.string(),
  code:          z.string(),
  type:          z.enum(['INTERNAL', 'EXTERNAL']),
  licenseNumber: z.string().nullable().optional(),
  email:         z.string().nullable().optional(),
  phone:         z.string().nullable().optional(),
  address:       z.string().nullable().optional(),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type AdjusterDto = z.infer<typeof AdjusterDtoSchema>;

// Mirrors com.nubeero.cia.setup.org.AgentType (V48). Agents represent the
// INSURER and earn commission on policies sold, distinct from Brokers
// (who represent the INSURED). Type is the legal form, not engagement model.
export type AgentType = 'INDIVIDUAL' | 'CORPORATE';

// Mirrors com.nubeero.cia.setup.org.dto.AgentResponse (V48).
export const AgentDtoSchema = z.object({
  id:            z.string(),
  name:          z.string(),
  code:          z.string(),
  type:          z.enum(['INDIVIDUAL', 'CORPORATE']),
  licenseNumber: z.string().nullable().optional(),
  email:         z.string().nullable().optional(),
  phone:         z.string().nullable().optional(),
  address:       z.string().nullable().optional(),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type AgentDto = z.infer<typeof AgentDtoSchema>;

// Policy clause bank (V72). Mirrors com.nubeero.cia.setup.policy.* enums.
export type ClauseType          = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';
export type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';

// Mirrors com.nubeero.cia.setup.policy.dto.ClauseResponse (V72).
export const ClauseDtoSchema = z.object({
  id:            z.string(),
  title:         z.string(),
  text:          z.string(),
  type:          z.enum(['STANDARD', 'EXCLUSION', 'SPECIAL_CONDITION', 'WARRANTY']),
  applicability: z.enum(['MANDATORY', 'OPTIONAL']),
  productIds:    z.array(z.string()),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type ClauseDto = z.infer<typeof ClauseDtoSchema>;

// ClauseSnapshotDto (the frozen snapshot on quotes/policies) is defined canonically in ./policy.

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

export const SurveyorDtoSchema = z.object({
  id:            z.string(),
  name:          z.string(),
  type:          z.enum(['INTERNAL', 'EXTERNAL']),
  licenseNumber: z.string().nullable().optional(),
  email:         z.string().nullable().optional(),
  phone:         z.string().nullable().optional(),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type SurveyorDto = z.infer<typeof SurveyorDtoSchema>;

// Used by policy/coinsurance for the participant picker.
// Mirrors com.nubeero.cia.setup.org.dto.InsuranceCompanyResponse.
export const InsuranceCompanyDtoSchema = z.object({
  id:            z.string(),
  name:          z.string(),
  rcNumber:      z.string().nullable().optional(),
  naicomLicense: z.string().nullable().optional(),
  address:       z.string().nullable().optional(),
  email:         z.string().nullable().optional(),
  phone:         z.string().nullable().optional(),
  createdAt:     z.string(),
  updatedAt:     z.string().nullable().optional(),
});
export type InsuranceCompanyDto = z.infer<typeof InsuranceCompanyDtoSchema>;

// ── Notification Templates (F7-δ / R7 — Setup → Notification Templates tab) ─
//
// Mirrors com.nubeero.cia.setup.notification.dto.* 1:1.
// Enums mirror cia-common NotificationTemplateType + NotificationChannel.
// NOTE: BaseEntity exposes createdBy but NOT updatedBy — updatedBy is absent
// from NotificationTemplateResponse and therefore absent here too.

// ── Enums ─────────────────────────────────────────────────────────────────

export const NotificationTemplateTypeSchema = z.enum(['RECEIPT', 'PAYMENT_VOUCHER']);
export type NotificationTemplateType = z.infer<typeof NotificationTemplateTypeSchema>;

export const NotificationChannelSchema = z.enum(['EMAIL', 'SMS']);
export type NotificationChannel = z.infer<typeof NotificationChannelSchema>;

// ── Main response schema ───────────────────────────────────────────────────

export const NotificationTemplateResponseSchema = z.object({
  id:              z.string(),
  templateType:    NotificationTemplateTypeSchema,
  channel:         NotificationChannelSchema,
  subjectTemplate: z.string().nullable(),
  bodyTemplate:    z.string().nullable(),
  createdAt:       z.string(),
  updatedAt:       z.string(),
  createdBy:       z.string().nullable(),
});

// Drift-check alias: maps NotificationTemplateDto → NotificationTemplateResponse.java
// (the drift script strips 'Dto' and appends 'Response' to derive the backend file name).
export type NotificationTemplateDto = z.infer<typeof NotificationTemplateResponseSchema>;

// ── Request + helper schemas ───────────────────────────────────────────────

export const NotificationTemplateRequestSchema = z.object({
  templateType:    NotificationTemplateTypeSchema,
  channel:         NotificationChannelSchema,
  subjectTemplate: z.string().nullable().optional(),
  bodyTemplate:    z.string().nullable().optional(),
});
export type NotificationTemplateRequest = z.infer<typeof NotificationTemplateRequestSchema>;

// Mirrors NotificationTemplateDefaultsResponse { defaults: Entry[] }
// where Entry = { templateType, channel, subjectTemplate, bodyTemplate }.
const NotificationTemplateDefaultsEntrySchema = z.object({
  templateType:    NotificationTemplateTypeSchema,
  channel:         NotificationChannelSchema,
  subjectTemplate: z.string().nullable(),
  bodyTemplate:    z.string(),
});

export const NotificationTemplateDefaultsResponseSchema = z.object({
  defaults: z.array(NotificationTemplateDefaultsEntrySchema),
});
export type NotificationTemplateDefaultsResponse = z.infer<typeof NotificationTemplateDefaultsResponseSchema>;

// Mirrors NotificationTemplateVariablesResponse { variables: Entry[] }
// where Entry = { templateType, channel, allowedVariables: string[] }.
const NotificationTemplateVariablesEntrySchema = z.object({
  templateType:     NotificationTemplateTypeSchema,
  channel:          NotificationChannelSchema,
  allowedVariables: z.array(z.string()),
});

export const NotificationTemplateVariablesResponseSchema = z.object({
  variables: z.array(NotificationTemplateVariablesEntrySchema),
});
export type NotificationTemplateVariablesResponse = z.infer<typeof NotificationTemplateVariablesResponseSchema>;

// Mirrors NotificationTemplatePreviewRequest { templateType, channel,
// subjectTemplate?, bodyTemplate?, sampleValues: Map<String,Object> }.
export const NotificationTemplatePreviewRequestSchema = z.object({
  templateType:    NotificationTemplateTypeSchema,
  channel:         NotificationChannelSchema,
  subjectTemplate: z.string().nullable().optional(),
  bodyTemplate:    z.string().nullable().optional(),
  sampleValues:    z.record(z.string(), z.unknown()),
});
export type NotificationTemplatePreviewRequest = z.infer<typeof NotificationTemplatePreviewRequestSchema>;

// Mirrors NotificationTemplatePreviewResponse { subject, body }.
export const NotificationTemplatePreviewResponseSchema = z.object({
  subject: z.string().nullable(),
  body:    z.string(),
});
export type NotificationTemplatePreviewResponse = z.infer<typeof NotificationTemplatePreviewResponseSchema>;

// ── Fetchers ───────────────────────────────────────────────────────────────

/**
 * GET /api/v1/setup/notification-templates
 * Returns the full list of tenant notification template overrides (no
 * pagination — the set is small and bounded by templateType × channel).
 * Uses validatedGet + z.array because the controller returns
 * ApiResponse<List<NotificationTemplateResponse>> with no meta block.
 */
export async function listNotificationTemplates(): Promise<NotificationTemplateDto[]> {
  return validatedGet(
    '/api/v1/setup/notification-templates',
    z.array(NotificationTemplateResponseSchema),
  );
}

/**
 * GET /api/v1/setup/notification-templates/defaults
 * JAR-bundled defaults for all (templateType, channel) combinations.
 * Read-only reference — used by the UI to pre-fill the editor.
 */
export async function getNotificationTemplateDefaults(): Promise<NotificationTemplateDefaultsResponse> {
  return validatedGet(
    '/api/v1/setup/notification-templates/defaults',
    NotificationTemplateDefaultsResponseSchema,
  );
}

/**
 * GET /api/v1/setup/notification-templates/variables
 * Allowed Mustache variable names per (templateType, channel).
 * Drives the variable-picker UI in the template editor.
 */
export async function getNotificationTemplateVariables(): Promise<NotificationTemplateVariablesResponse> {
  return validatedGet(
    '/api/v1/setup/notification-templates/variables',
    NotificationTemplateVariablesResponseSchema,
  );
}

/**
 * POST /api/v1/setup/notification-templates
 * Creates a tenant override for a (templateType, channel) pair.
 */
export async function createNotificationTemplate(
  req: NotificationTemplateRequest,
): Promise<NotificationTemplateDto> {
  return validatedPost(
    '/api/v1/setup/notification-templates',
    req,
    NotificationTemplateResponseSchema,
  );
}

/**
 * PUT /api/v1/setup/notification-templates/{id}
 * Updates an existing tenant override.
 */
export async function updateNotificationTemplate(
  id:  string,
  req: NotificationTemplateRequest,
): Promise<NotificationTemplateDto> {
  return validatedPut(
    `/api/v1/setup/notification-templates/${id}`,
    req,
    NotificationTemplateResponseSchema,
  );
}

/**
 * DELETE /api/v1/setup/notification-templates/{id}?reason=
 * Deletes (resets) a tenant override — restores the JAR-bundled default.
 * `reason` is optional at the API level but required by the UI
 * (ConfirmDeleteDialog + useDeleteWithReason hook).
 */
export async function deleteNotificationTemplate(
  id:      string,
  reason?: string,
): Promise<void> {
  await apiClient.delete(
    `/api/v1/setup/notification-templates/${id}`,
    { params: reason ? { reason } : undefined },
  );
}

/**
 * POST /api/v1/setup/notification-templates/preview
 * Renders a template body (and optional subject) with sample variable values.
 * Used by the editor to show a live preview before saving.
 */
export async function previewNotificationTemplate(
  req: NotificationTemplatePreviewRequest,
): Promise<NotificationTemplatePreviewResponse> {
  return validatedPost(
    '/api/v1/setup/notification-templates/preview',
    req,
    NotificationTemplatePreviewResponseSchema,
  );
}
