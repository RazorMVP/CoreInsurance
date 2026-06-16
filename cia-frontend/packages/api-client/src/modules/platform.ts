// ── Platform Admin (SP2) ──────────────────────────────────────────────────
//
// Wire shapes + React Query hooks for the cross-tenant platform-admin API at
// /api/v1/platform/**. Field names mirror the Java DTOs in cia-api/.../platform/
// (TenantSummary, TenantDetailResponse, PlatformAuditEntry, OnboardTenant*,
// TenantStats, SuperAdminSummary, InviteSuperAdmin*). Schemas are the source of
// truth; fetch with validatedGet/validatedList so backend drift fails loudly.

import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../client';
import { validatedGet, validatedList, validatedPost } from '../validation';

// ── Schemas ───────────────────────────────────────────────────────────────

export const TenantSummarySchema = z.object({
  schema:      z.string(),
  displayName: z.string(),
  subdomain:   z.string(),
  active:      z.boolean(),
  createdAt:   z.string(),
});

export const PlatformAuditEntrySchema = z.object({
  id:            z.string(),
  action:        z.string(),
  targetSchema:  z.string().nullable(),
  actorUsername: z.string(),
  actorRealm:    z.string(),
  detail:        z.string().nullable(),
  sourceIp:      z.string().nullable(),
  at:            z.string(),
});

export const TenantDetailSchema = z.object({
  tenant:      TenantSummarySchema,
  recentAudit: z.array(PlatformAuditEntrySchema),
});

export const FirstAdminSchema = z.object({
  username:          z.string(),
  email:             z.string(),
  temporaryPassword: z.string(),
});

export const OnboardTenantResponseSchema = z.object({
  tenant:     TenantSummarySchema,
  firstAdmin: FirstAdminSchema,
});

export const TenantStatsSchema = z.object({
  total:     z.number(),
  active:    z.number(),
  suspended: z.number(),
});

export const SuperAdminSummarySchema = z.object({
  username: z.string(),
  email:    z.string(),
  enabled:  z.boolean(),
});

export const InviteSuperAdminResponseSchema = z.object({
  username:          z.string(),
  email:             z.string(),
  temporaryPassword: z.string(),
});

export type TenantSummary           = z.infer<typeof TenantSummarySchema>;
export type PlatformAuditEntry      = z.infer<typeof PlatformAuditEntrySchema>;
export type TenantDetail            = z.infer<typeof TenantDetailSchema>;
export type OnboardTenantResponse   = z.infer<typeof OnboardTenantResponseSchema>;
export type TenantStats             = z.infer<typeof TenantStatsSchema>;
export type SuperAdminSummary       = z.infer<typeof SuperAdminSummarySchema>;
export type InviteSuperAdminResponse= z.infer<typeof InviteSuperAdminResponseSchema>;

export interface OnboardTenantRequest {
  schema: string;
  realm?: string;
  displayName: string;
  subdomain: string;
  adminUsername: string;
  adminEmail: string;
}
export interface InviteSuperAdminRequest { username: string; email: string; }

// ── Error helper ──────────────────────────────────────────────────────────

/** Pull the structured backend errorCode (e.g. CANNOT_REVOKE_SELF) off an axios error. */
export function platformErrorCode(err: unknown): string | undefined {
  const e = err as { response?: { data?: { errors?: { code?: string }[] } } };
  return e?.response?.data?.errors?.[0]?.code;
}

// ── Query hooks ───────────────────────────────────────────────────────────

const PLATFORM = '/api/v1/platform';

export function useTenants(page: number, size = 50) {
  return useQuery({
    queryKey: ['platform', 'tenants', page, size],
    queryFn: () => validatedList(`${PLATFORM}/tenants`, TenantSummarySchema, { params: { page, size } }),
    staleTime: 30_000,
  });
}

export function useTenantDetail(schema: string | undefined) {
  return useQuery({
    queryKey: ['platform', 'tenant', schema],
    queryFn: () => validatedGet(`${PLATFORM}/tenants/${schema}`, TenantDetailSchema),
    enabled: !!schema,
  });
}

export function usePlatformStats() {
  return useQuery({
    queryKey: ['platform', 'stats'],
    queryFn: () => validatedGet(`${PLATFORM}/stats`, TenantStatsSchema),
    staleTime: 30_000,
  });
}

export function usePlatformAudit(page: number, size = 50, targetSchema?: string) {
  return useQuery({
    queryKey: ['platform', 'audit', page, size, targetSchema ?? null],
    queryFn: () => validatedList(`${PLATFORM}/audit`, PlatformAuditEntrySchema, {
      params: { page, size, ...(targetSchema ? { targetSchema } : {}) },
    }),
    staleTime: 15_000,
  });
}

export function useSuperAdmins() {
  return useQuery({
    queryKey: ['platform', 'super-admins'],
    queryFn: () => validatedGet(`${PLATFORM}/super-admins`, z.array(SuperAdminSummarySchema)),
  });
}

// ── Mutation hooks ────────────────────────────────────────────────────────

export function useOnboardTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: OnboardTenantRequest) =>
      validatedPost(`${PLATFORM}/tenants`, body, OnboardTenantResponseSchema),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useSuspendTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (schema: string) => apiClient.post(`${PLATFORM}/tenants/${schema}/suspend`),
    onSuccess: (_d, schema) => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'tenant', schema] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useActivateTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (schema: string) => apiClient.post(`${PLATFORM}/tenants/${schema}/activate`),
    onSuccess: (_d, schema) => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'tenant', schema] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useInviteSuperAdmin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: InviteSuperAdminRequest) =>
      validatedPost(`${PLATFORM}/super-admins`, body, InviteSuperAdminResponseSchema),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'super-admins'] }),
  });
}

export function useRevokeSuperAdmin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (username: string) => apiClient.delete(`${PLATFORM}/super-admins/${username}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'super-admins'] }),
  });
}
