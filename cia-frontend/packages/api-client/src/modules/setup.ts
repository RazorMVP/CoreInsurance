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

// Policy clause bank (V72). Mirrors com.nubeero.cia.setup.policy.* enums.
export type ClauseType          = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';
export type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';

// Mirrors com.nubeero.cia.setup.policy.dto.ClauseResponse (V72).
export interface ClauseDto {
  id:            string;
  title:         string;
  text:          string;
  type:          ClauseType;
  applicability: ClauseApplicability;
  productIds:    string[];
  createdAt:     string;
  updatedAt?:    string | null;
}

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
