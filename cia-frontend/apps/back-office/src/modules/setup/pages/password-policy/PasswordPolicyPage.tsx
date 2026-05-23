import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Card, CardContent, CardDescription, CardHeader, CardTitle,
  Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage,
  FormRow, FormSection, Input, PageHeader, Separator, Skeleton, Switch,
} from '@cia/ui';
import { apiClient, type PasswordPolicyDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

// Mirrors com.nubeero.cia.setup.company.dto.PasswordPolicyRequest 1:1.
// Bookkeeping-only: storage round-trips here but the actual login-time
// enforcement is owned by Keycloak's realm password policy. The slice
// notice card below makes that explicit so admins don't think changing
// these knobs blocks weak passwords at login. Wiring Keycloak realm sync
// is a separate slice (backlog F4-sync).
const schema = z
  .object({
    minLength:         z.coerce.number().int().min(4).max(256),
    maxLength:         z.coerce.number().int().min(4).max(256),
    requireUppercase:  z.boolean(),
    requireLowercase:  z.boolean(),
    requireNumbers:    z.boolean(),
    requireSpecial:    z.boolean(),
    expiryDays:        z.coerce.number().int().min(0).max(3650),
    maxFailedAttempts: z.coerce.number().int().min(1).max(100),
  })
  .refine((v) => v.maxLength >= v.minLength, {
    path:    ['maxLength'],
    message: 'Maximum length must be ≥ minimum length',
  });

type FormValues = z.infer<typeof schema>;

// Mirror the backend V3 DDL defaults so the form has sensible initial values
// while the GET is in flight (eliminates the empty-shape flash on first render).
const FALLBACK_DEFAULTS: FormValues = {
  minLength:         8,
  maxLength:         128,
  requireUppercase:  true,
  requireLowercase:  true,
  requireNumbers:    true,
  requireSpecial:    false,
  expiryDays:        90,
  maxFailedAttempts: 5,
};

export default function PasswordPolicyPage() {
  const queryClient = useQueryClient();

  const policyQuery = useQuery<PasswordPolicyDto>({
    queryKey: ['setup', 'password-policy'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PasswordPolicyDto }>(
        '/api/v1/setup/password-policy',
      );
      return res.data.data;
    },
  });
  const isLoading = policyQuery.isLoading;

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: FALLBACK_DEFAULTS,
  });

  useEffect(() => {
    const p = policyQuery.data;
    if (!p) return;
    form.reset({
      minLength:         p.minLength,
      maxLength:         p.maxLength,
      requireUppercase:  p.requireUppercase,
      requireLowercase:  p.requireLowercase,
      requireNumbers:    p.requireNumbers,
      requireSpecial:    p.requireSpecial,
      expiryDays:        p.expiryDays,
      maxFailedAttempts: p.maxFailedAttempts,
    });
  }, [policyQuery.data, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.put<{ data: PasswordPolicyDto }>(
        '/api/v1/setup/password-policy', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'password-policy'] });
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not save policy' }),
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  if (isLoading) {
    return (
      <div className="space-y-4 p-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-48 w-full rounded-lg" />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6 max-w-3xl">
      <PageHeader
        title="Password Policy"
        description="Tenant-side password-policy bookkeeping for the company directory."
      />

      <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
        <p className="font-medium">Bookkeeping only</p>
        <p className="mt-1 text-amber-800">
          Actual login-time enforcement is governed by Keycloak's realm password policy.
          These settings are stored for tenant reporting and future Keycloak realm sync.
          They do not change what Keycloak accepts at sign-in today.
        </p>
      </div>

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Length &amp; character requirements</CardTitle>
              <CardDescription>
                Minimum / maximum length and which character classes are required.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <FormSection>
                <FormRow>
                  <FormField
                    control={form.control}
                    name="minLength"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Minimum length</FormLabel>
                        <FormControl>
                          <Input type="number" min={4} max={256} {...field} />
                        </FormControl>
                        <FormDescription>Between 4 and 256 characters.</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="maxLength"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Maximum length</FormLabel>
                        <FormControl>
                          <Input type="number" min={4} max={256} {...field} />
                        </FormControl>
                        <FormDescription>Must be ≥ minimum length.</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>

                <FormField
                  control={form.control}
                  name="requireUppercase"
                  render={({ field }) => (
                    <FormItem className="flex items-center justify-between rounded-md border p-3">
                      <div className="space-y-0.5">
                        <FormLabel>Require uppercase</FormLabel>
                        <FormDescription>At least one A–Z character.</FormDescription>
                      </div>
                      <FormControl>
                        <Switch checked={field.value} onCheckedChange={field.onChange} />
                      </FormControl>
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="requireLowercase"
                  render={({ field }) => (
                    <FormItem className="flex items-center justify-between rounded-md border p-3">
                      <div className="space-y-0.5">
                        <FormLabel>Require lowercase</FormLabel>
                        <FormDescription>At least one a–z character.</FormDescription>
                      </div>
                      <FormControl>
                        <Switch checked={field.value} onCheckedChange={field.onChange} />
                      </FormControl>
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="requireNumbers"
                  render={({ field }) => (
                    <FormItem className="flex items-center justify-between rounded-md border p-3">
                      <div className="space-y-0.5">
                        <FormLabel>Require numbers</FormLabel>
                        <FormDescription>At least one 0–9 digit.</FormDescription>
                      </div>
                      <FormControl>
                        <Switch checked={field.value} onCheckedChange={field.onChange} />
                      </FormControl>
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="requireSpecial"
                  render={({ field }) => (
                    <FormItem className="flex items-center justify-between rounded-md border p-3">
                      <div className="space-y-0.5">
                        <FormLabel>Require special character</FormLabel>
                        <FormDescription>At least one non-alphanumeric character.</FormDescription>
                      </div>
                      <FormControl>
                        <Switch checked={field.value} onCheckedChange={field.onChange} />
                      </FormControl>
                    </FormItem>
                  )}
                />
              </FormSection>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Lifetime &amp; lockout</CardTitle>
              <CardDescription>
                How long passwords stay valid and how many failed attempts lock the account.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <FormSection>
                <FormRow>
                  <FormField
                    control={form.control}
                    name="expiryDays"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Expires after (days)</FormLabel>
                        <FormControl>
                          <Input type="number" min={0} max={3650} {...field} />
                        </FormControl>
                        <FormDescription>0 = passwords never expire.</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="maxFailedAttempts"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Max failed sign-in attempts</FormLabel>
                        <FormControl>
                          <Input type="number" min={1} max={100} {...field} />
                        </FormControl>
                        <FormDescription>Between 1 and 100.</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>
              </FormSection>
            </CardContent>
          </Card>

          <Separator />

          <div className="flex justify-end">
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save Policy'}
            </Button>
          </div>
        </form>
      </Form>
    </div>
  );
}
