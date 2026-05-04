import type { FieldValues, Path, UseFormReturn } from 'react-hook-form';
import { toast } from '@cia/ui';
import type { ApiError, ApiResponse } from '@cia/api-client';

// Structural axios-like error shape — avoids a direct axios dep in this app.
interface ApiHttpError {
  message?: string;
  response?: { status?: number; data?: ApiResponse<unknown> | undefined };
}

interface ApplyOptions {
  /** Title used for the toast when no field-level errors mapped. */
  defaultTitle?: string;
}

/**
 * Map a failed API mutation onto a react-hook-form instance.
 *
 * - For each `{ field, message }` returned in `response.data.errors`, sets a
 *   server-side error on that field via `form.setError`. The field error
 *   surfaces under the same `<FormMessage />` as Zod validation messages.
 * - If no field-level errors were mapped (e.g. 500 response, network error,
 *   or 400 with form-level error only), shows a destructive toast with the
 *   server's message — or a generic fallback for non-API errors.
 *
 * Backend contract (cia-common ApiResponse): `errors: [{ code, message, field? }]`.
 * The `field` is the form field path (dot-notation supported).
 */
export function applyApiErrors<T extends FieldValues>(
  error: unknown,
  form: UseFormReturn<T>,
  opts: ApplyOptions = {},
): void {
  const ax = error as ApiHttpError | undefined;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];

  let mappedAny = false;
  for (const e of errors) {
    if (e.field) {
      form.setError(e.field as Path<T>, { type: 'server', message: e.message });
      mappedAny = true;
    }
  }

  if (mappedAny) return;

  const description =
    errors.length > 0
      ? errors.map((e: ApiError) => e.message).filter(Boolean).join('. ')
      : ax?.message ?? 'An unexpected error occurred. Please try again.';

  toast({
    variant: 'destructive',
    title: opts.defaultTitle ?? 'Save failed',
    description,
  });
}
