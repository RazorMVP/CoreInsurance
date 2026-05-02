import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Card, CardContent, CardDescription, CardHeader, CardTitle,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormSection, FormRow, Input, PageHeader, Separator, Skeleton, Textarea,
} from '@cia/ui';
import { apiClient, type CompanySettingsDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

const schema = z.object({
  companyName:         z.string().min(2, 'Required'),
  address:             z.string().min(5, 'Required'),
  email:               z.string().email('Invalid email'),
  phone:               z.string().min(7, 'Required'),
  website:             z.string().url('Invalid URL').optional().or(z.literal('')),
  defaultCurrencyCode: z.string().length(3, 'Must be a 3-letter currency code'),
  minPasswordLength:   z.coerce.number().min(8).max(32),
  passwordExpireDays:  z.coerce.number().min(0).max(365),
});

type FormValues = z.infer<typeof schema>;

interface CompanySettingsResponse extends CompanySettingsDto {
  minPasswordLength?:  number;
  passwordExpireDays?: number;
}

const FALLBACK_DEFAULTS: FormValues = {
  companyName:         '',
  address:             '',
  email:               '',
  phone:               '',
  website:             '',
  defaultCurrencyCode: 'NGN',
  minPasswordLength:   8,
  passwordExpireDays:  90,
};

export default function CompanySettingsPage() {
  const queryClient = useQueryClient();

  const settingsQuery = useQuery<CompanySettingsResponse>({
    queryKey: ['setup', 'company-settings'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CompanySettingsResponse }>(
        '/api/v1/setup/company-settings',
      );
      return res.data.data;
    },
  });
  const isLoading = settingsQuery.isLoading;

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: FALLBACK_DEFAULTS,
  });

  useEffect(() => {
    const s = settingsQuery.data;
    if (!s) return;
    form.reset({
      companyName:         s.companyName,
      address:             s.address,
      email:               s.email,
      phone:               s.phone,
      website:             s.website ?? '',
      defaultCurrencyCode: s.defaultCurrencyCode,
      minPasswordLength:   s.minPasswordLength ?? 8,
      passwordExpireDays:  s.passwordExpireDays ?? 90,
    });
  }, [settingsQuery.data, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.put<{ data: CompanySettingsResponse }>(
        '/api/v1/setup/company-settings', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'company-settings'] });
    },
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
        title="Company Settings"
        description="Manage your insurance company profile and system defaults."
      />

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          {/* Company profile */}
          <Card>
            <CardHeader>
              <CardTitle>Company Profile</CardTitle>
              <CardDescription>Basic details displayed on policy documents and reports.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <FormSection>
                <FormField
                  control={form.control}
                  name="companyName"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Company Name</FormLabel>
                      <FormControl><Input placeholder="e.g. Acme Insurance Ltd" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormRow>
                  <FormField
                    control={form.control}
                    name="email"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Email Address</FormLabel>
                        <FormControl><Input type="email" placeholder="info@company.com" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="phone"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Phone</FormLabel>
                        <FormControl><Input placeholder="+234 800 000 0000" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>
                <FormField
                  control={form.control}
                  name="address"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Address</FormLabel>
                      <FormControl><Textarea rows={3} placeholder="Full postal address" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormRow>
                  <FormField
                    control={form.control}
                    name="website"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Website (optional)</FormLabel>
                        <FormControl><Input placeholder="https://company.com" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="defaultCurrencyCode"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Default Currency</FormLabel>
                        <FormControl><Input placeholder="NGN" maxLength={3} className="uppercase" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>
              </FormSection>
            </CardContent>
          </Card>

          {/* Password policy */}
          <Card>
            <CardHeader>
              <CardTitle>Password Policy</CardTitle>
              <CardDescription>Minimum requirements enforced for all user passwords.</CardDescription>
            </CardHeader>
            <CardContent>
              <FormRow>
                <FormField
                  control={form.control}
                  name="minPasswordLength"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Minimum Length</FormLabel>
                      <FormControl><Input type="number" min={8} max={32} {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="passwordExpireDays"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Expiry (days, 0 = never)</FormLabel>
                      <FormControl><Input type="number" min={0} max={365} {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </FormRow>
            </CardContent>
          </Card>

          <Separator />

          <div className="flex justify-end">
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {form.formState.isSubmitting ? 'Saving…' : 'Save Settings'}
            </Button>
          </div>
        </form>
      </Form>
    </div>
  );
}
