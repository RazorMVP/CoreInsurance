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
import { applyApiErrors } from '@/lib/form-errors';

// Mirrors com.nubeero.cia.setup.company.dto.CompanySettingsRequest 1:1.
// Aligned with backend in Session 98 / Backlog A1c — previously sent
// `companyName` (backend silently dropped it; backend takes `name`) and a
// `defaultCurrencyCode` field that doesn't exist on the request at all.
// Password-policy settings now live at /setup/password-policy (backlog F4).
const schema = z.object({
  name:                z.string().min(2, 'Required'),
  rcNumber:            z.string().optional().or(z.literal('')),
  naicomLicenseNumber: z.string().optional().or(z.literal('')),
  address:             z.string().optional().or(z.literal('')),
  city:                z.string().optional().or(z.literal('')),
  state:               z.string().optional().or(z.literal('')),
  email:               z.string().email('Invalid email').optional().or(z.literal('')),
  phone:               z.string().optional().or(z.literal('')),
  logoPath:            z.string().optional().or(z.literal('')),
  website:             z.string().url('Invalid URL').optional().or(z.literal('')),
});

type FormValues = z.infer<typeof schema>;

const FALLBACK_DEFAULTS: FormValues = {
  name:                '',
  rcNumber:            '',
  naicomLicenseNumber: '',
  address:             '',
  city:                '',
  state:               '',
  email:               '',
  phone:               '',
  logoPath:            '',
  website:             '',
};

export default function CompanySettingsPage() {
  const queryClient = useQueryClient();

  const settingsQuery = useQuery<CompanySettingsDto>({
    queryKey: ['setup', 'company-settings'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CompanySettingsDto }>(
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
      name:                s.name,
      rcNumber:            s.rcNumber ?? '',
      naicomLicenseNumber: s.naicomLicenseNumber ?? '',
      address:             s.address ?? '',
      city:                s.city ?? '',
      state:               s.state ?? '',
      email:               s.email ?? '',
      phone:               s.phone ?? '',
      logoPath:            s.logoPath ?? '',
      website:             s.website ?? '',
    });
  }, [settingsQuery.data, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.put<{ data: CompanySettingsDto }>(
        '/api/v1/setup/company-settings', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'company-settings'] });
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not save settings' }),
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
        description="Manage your insurance company profile."
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
                  name="name"
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
                    name="rcNumber"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>RC Number</FormLabel>
                        <FormControl><Input placeholder="e.g. RC123456" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="naicomLicenseNumber"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>NAICOM Licence</FormLabel>
                        <FormControl><Input placeholder="e.g. RIC/AB/00000/2026" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>
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
                      <FormControl><Textarea rows={2} placeholder="Street address" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormRow>
                  <FormField
                    control={form.control}
                    name="city"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>City</FormLabel>
                        <FormControl><Input placeholder="e.g. Lagos" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="state"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>State</FormLabel>
                        <FormControl><Input placeholder="e.g. Lagos" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </FormRow>
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
                    name="logoPath"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Logo Path (optional)</FormLabel>
                        <FormControl><Input placeholder="/uploads/logo.png" {...field} /></FormControl>
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
              {save.isPending ? 'Saving…' : 'Save Settings'}
            </Button>
          </div>
        </form>
      </Form>
    </div>
  );
}
