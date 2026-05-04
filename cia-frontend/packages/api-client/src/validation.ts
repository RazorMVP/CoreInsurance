import { z } from 'zod';
import type { AxiosRequestConfig } from 'axios';
import { apiClient } from './client';

// ─── Envelope ────────────────────────────────────────────────────────────────
//
// Every backend response uses the same envelope:
//   { data: T, meta?: ApiMeta, errors?: ApiError[] }
//
// The schemas below mirror cia-common's ApiResponse shape. They're used by
// `apiEnvelope()` and the `validatedGet`/`validatedPost`/etc. helpers to
// validate responses *at runtime* — drift between frontend and backend
// surfaces as a ZodError instead of silently passing `undefined` to the UI
// (which is how the finance + reinsurance contract bugs went unnoticed for
// weeks; see session 53 G8).

const apiErrorSchema = z.object({
  code:    z.string().optional(),
  message: z.string(),
  field:   z.string().optional(),
});

const apiMetaSchema = z.object({
  page:       z.number().optional(),
  size:       z.number().optional(),
  total:      z.number().optional(),
  totalPages: z.number().optional(),
});

/**
 * Wrap a data schema in the standard CIA response envelope.
 *
 * @example
 *   const envelope = apiEnvelope(z.array(DebitNoteDtoSchema));
 *   const { data } = envelope.parse(res.data);
 */
export function apiEnvelope<T extends z.ZodTypeAny>(dataSchema: T) {
  return z.object({
    data:   dataSchema,
    meta:   apiMetaSchema.optional(),
    errors: z.array(apiErrorSchema).optional(),
  });
}

// ─── Validated request helpers ───────────────────────────────────────────────
//
// Drop-in replacements for `apiClient.get(url).then(r => r.data.data)`. Each
// helper validates the response envelope against the supplied schema and
// returns just the `data` field. Throws ZodError on shape mismatch.

/**
 * GET + validate. Returns just the `data` field of the envelope.
 *
 * @example
 *   const debitNotes = await validatedGet(
 *     '/api/v1/finance/debit-notes',
 *     z.array(DebitNoteDtoSchema),
 *   );
 */
export async function validatedGet<T extends z.ZodTypeAny>(
  url:     string,
  schema:  T,
  config?: AxiosRequestConfig,
): Promise<z.infer<T>> {
  const res = await apiClient.get(url, config);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}

/** POST + validate. Returns just the `data` field of the envelope. */
export async function validatedPost<T extends z.ZodTypeAny>(
  url:     string,
  body:    unknown,
  schema:  T,
  config?: AxiosRequestConfig,
): Promise<z.infer<T>> {
  const res = await apiClient.post(url, body, config);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}

/** PUT + validate. Returns just the `data` field of the envelope. */
export async function validatedPut<T extends z.ZodTypeAny>(
  url:     string,
  body:    unknown,
  schema:  T,
  config?: AxiosRequestConfig,
): Promise<z.infer<T>> {
  const res = await apiClient.put(url, body, config);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}

/** PATCH + validate. Returns just the `data` field of the envelope. */
export async function validatedPatch<T extends z.ZodTypeAny>(
  url:     string,
  body:    unknown,
  schema:  T,
  config?: AxiosRequestConfig,
): Promise<z.infer<T>> {
  const res = await apiClient.patch(url, body, config);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}
