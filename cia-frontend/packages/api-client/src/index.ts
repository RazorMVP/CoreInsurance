// @cia/api-client — backend integration surface for the back-office app.
//
// ── How to fetch ─────────────────────────────────────────────────────────────
//
// PREFER `validatedGet` over `apiClient.get` for typed reads. It validates
// the response shape against a zod schema, so frontend/backend drift fails
// loudly at runtime instead of silently passing `undefined` to the UI:
//
//   import { useQuery } from '@tanstack/react-query';
//   import { z } from 'zod';
//   import { validatedGet, DebitNoteDtoSchema } from '@cia/api-client';
//
//   const debitNotesQuery = useQuery({
//     queryKey: ['finance', 'debit-notes'],
//     queryFn: () => validatedGet('/api/v1/finance/debit-notes', z.array(DebitNoteDtoSchema)),
//   });
//
// Why we validate: in May 2026 we discovered the finance + reinsurance
// frontend DTOs had drifted from the backend response shape. List pages
// silently rendered empty cells because TypeScript can't catch JSON shape
// drift at compile time — `apiClient.get<T>(...)` is a type assertion, not
// a runtime check. Use the validated helpers for any new code; migrate
// existing calls when you touch the surrounding module.
//
// `apiClient` (raw axios) is still exported for one-off calls where a
// schema is overkill — but the validated path is the default.

export { apiClient, createApiClient, initApiClient, setTokenGetter } from './client';
export type { ApiResponse, ApiMeta, ApiError, PageResponse } from './types';
export { useGet, useList, useCreate, useUpdate, useRemove } from './hooks';
export {
  apiEnvelope,
  validatedGet, validatedPost, validatedPut, validatedPatch,
} from './validation';
export * from './modules';
