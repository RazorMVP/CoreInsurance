// ── Audit — schemas + derived types ──────────────────────────────────────
//
// Mirrors cia-audit/dto/* records. Serves at /api/v1/audit/...
//
// Backend conventions:
//   - Listing endpoints return `Page<T>` (Spring Data) wrapped in
//     ApiResponse: `{ data: { content: [], totalElements, ... }, meta? }`.
//     The frontend reads `res.data.data.content` to get the array.
//   - The user-activity report endpoint returns a flat `List<T>`, no
//     Page wrapper.
//
// Note: AlertType backend enum is FAILED_LOGIN (singular) — earlier
// hand-rolled frontend interface had FAILED_LOGINS (plural). Aligning
// to backend here.

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const AuditActionSchema = z.enum([
  'CREATE', 'UPDATE', 'DELETE', 'APPROVE', 'REJECT',
  'SUBMIT', 'SEND', 'CANCEL', 'REVERSE', 'EXECUTE',
]);
export type AuditAction = z.infer<typeof AuditActionSchema>;

export const LoginEventTypeSchema = z.enum([
  'LOGIN', 'LOGOUT', 'LOGIN_FAILED', 'PASSWORD_RESET', 'ACCOUNT_LOCKED',
]);
export type LoginEventType = z.infer<typeof LoginEventTypeSchema>;

export const AlertTypeSchema = z.enum([
  'FAILED_LOGIN', 'BULK_DELETE', 'OFF_HOURS_ACTIVITY', 'LARGE_FINANCIAL_APPROVAL',
]);
export type AlertType = z.infer<typeof AlertTypeSchema>;

// ── Audit log entry ───────────────────────────────────────────────────────

export const AuditLogDtoSchema = z.object({
  id:             z.string(),
  entityType:     z.string(),
  entityId:       z.string().nullable().optional(),
  action:         AuditActionSchema,
  userId:         z.string().nullable().optional(),
  userName:       z.string().nullable().optional(),
  timestamp:      z.string(),
  oldValue:       z.string().nullable().optional(),
  newValue:       z.string().nullable().optional(),
  ipAddress:      z.string().nullable().optional(),
  sessionId:      z.string().nullable().optional(),
  approvalAmount: z.number().nullable().optional(),
});
export type AuditLogDto = z.infer<typeof AuditLogDtoSchema>;

// ── Login audit log ───────────────────────────────────────────────────────

export const LoginAuditLogDtoSchema = z.object({
  id:            z.string(),
  eventType:     LoginEventTypeSchema,
  userId:        z.string().nullable().optional(),
  userName:      z.string().nullable().optional(),
  ipAddress:     z.string().nullable().optional(),
  userAgent:     z.string().nullable().optional(),
  timestamp:     z.string(),
  success:       z.boolean(),
  failureReason: z.string().nullable().optional(),
});
export type LoginAuditLogDto = z.infer<typeof LoginAuditLogDtoSchema>;

// ── User activity summary (aggregation report) ───────────────────────────

export const UserActivitySummaryDtoSchema = z.object({
  userId:      z.string().nullable().optional(),
  userName:    z.string().nullable().optional(),
  actionCount: z.number(),
});
export type UserActivitySummaryDto = z.infer<typeof UserActivitySummaryDtoSchema>;

// ── Audit alert ───────────────────────────────────────────────────────────

export const AuditAlertDtoSchema = z.object({
  id:               z.string(),
  alertType:        AlertTypeSchema,
  severity:         z.string(),                     // backend stores severity as String, not strict enum
  userId:           z.string().nullable().optional(),
  userName:         z.string().nullable().optional(),
  description:      z.string(),
  metadata:         z.string().nullable().optional(),
  triggeredAt:      z.string(),
  acknowledged:     z.boolean(),
  acknowledgedBy:   z.string().nullable().optional(),
  acknowledgedAt:   z.string().nullable().optional(),
});
export type AuditAlertDto = z.infer<typeof AuditAlertDtoSchema>;

// ── Spring Page envelope helper ──────────────────────────────────────────
//
// Some audit endpoints return `Page<T>`; others return a flat list.
// Use this when the backend returns Page:
//
//   const res = await apiClient.get(url, { params });
//   const page = pageSchema(AuditLogDtoSchema).parse(res.data.data);
//   return page.content;

export function pageSchema<T extends z.ZodTypeAny>(itemSchema: T) {
  return z.object({
    content:       z.array(itemSchema),
    totalElements: z.number().optional(),
    totalPages:    z.number().optional(),
    number:        z.number().optional(),
    size:          z.number().optional(),
  });
}
