import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Card, CardContent, CardHeader, CardTitle,
  Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage,
  Input, PageHeader, Separator, Switch,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

const schema = z.object({
  prefix:       z.string().min(1, 'Required').max(20),
  includeYear:  z.boolean(),
  includeType:  z.boolean(),
  sequenceLength: z.coerce.number().min(5, 'Minimum 5 digits').max(10, 'Maximum 10 digits'),
});
type FormValues = z.infer<typeof schema>;

interface FormatConfig {
  id?: string;
  prefix: string;
  includeYear: boolean;
  includeType: boolean;
  sequenceLength: number;
  lastSequence: number;
  lastSequenceIndividual: number;
  lastSequenceCorporate: number;
}

function buildPreview(values: FormValues, type: 'IND' | 'CORP'): string {
  const year  = new Date().getFullYear();
  const seq   = '0'.repeat(Math.max(0, values.sequenceLength - 1)) + '1';
  const parts = [values.prefix || 'CUST'];
  if (values.includeYear)  parts.push(String(year));
  if (values.includeType)  parts.push(type);
  parts.push(seq);
  return parts.join('/');
}

export default function CustomerNumberFormatPage() {
  const queryClient = useQueryClient();

  const { data, isLoading } = useQuery<{ data: FormatConfig | null }>({
    queryKey: ['customer-number-format'],
    queryFn:  () => apiClient.get('/api/v1/setup/customer-number-format').then(r => r.data),
  });

  const config = data?.data ?? null;

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { prefix: 'CUST', includeYear: true, includeType: true, sequenceLength: 8 },
  });

  useEffect(() => {
    if (config) {
      form.reset({
        prefix:         config.prefix,
        includeYear:    config.includeYear,
        includeType:    config.includeType,
        sequenceLength: config.sequenceLength,
      });
    }
  }, [config]);

  const values = form.watch();

  const previewInd  = useMemo(() => buildPreview(values, 'IND'),  [values]);
  const previewCorp = useMemo(() => buildPreview(values, 'CORP'), [values]);

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      apiClient.put('/api/v1/setup/customer-number-format', v).then(r => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['customer-number-format'] }),
  });

  return (
    <div className="p-6 max-w-2xl space-y-6">
      <PageHeader
        title="Customer Number Format"
        description="Configure how customer IDs are generated when a new customer is onboarded."
      />

      <Card>
        <CardHeader><CardTitle className="text-sm font-semibold">Format Configuration</CardTitle></CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : (
            <Form {...form}>
              <form onSubmit={form.handleSubmit(v => save.mutate(v))} className="space-y-5">

                <FormField control={form.control} name="prefix"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Prefix</FormLabel>
                      <FormControl><Input placeholder="CUST" {...field} /></FormControl>
                      <FormDescription className="text-xs">Short code prepended to every customer number (e.g. CUST).</FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField control={form.control} name="sequenceLength"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Sequence Digits</FormLabel>
                      <FormControl><Input type="number" min={5} max={10} {...field} /></FormControl>
                      <FormDescription className="text-xs">
                        Number of zero-padded digits in the sequence. 8 digits supports up to 99,999,999 customers per type per year.
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <div className="flex flex-col gap-4">
                  <FormField control={form.control} name="includeYear"
                    render={({ field }) => (
                      <FormItem className="flex items-center justify-between rounded-lg border p-4">
                        <div>
                          <FormLabel>Include Year</FormLabel>
                          <FormDescription className="text-xs">Appends the current year segment (e.g. /2026/).</FormDescription>
                        </div>
                        <FormControl>
                          <Switch checked={field.value} onCheckedChange={field.onChange} />
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField control={form.control} name="includeType"
                    render={({ field }) => (
                      <FormItem className="flex items-center justify-between rounded-lg border p-4">
                        <div>
                          <FormLabel>Include Customer Type</FormLabel>
                          <FormDescription className="text-xs">
                            Appends IND or CORP and uses separate sequences per type.
                          </FormDescription>
                        </div>
                        <FormControl>
                          <Switch checked={field.value} onCheckedChange={field.onChange} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <Separator />

                {/* Live preview */}
                <div className="rounded-lg bg-secondary/40 p-4 space-y-2">
                  <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Live Preview</p>
                  <div className="flex flex-col gap-1">
                    <div className="flex items-center gap-3">
                      <span className="text-xs w-20 text-muted-foreground">Individual</span>
                      <code className="text-sm font-mono text-foreground">{previewInd}</code>
                    </div>
                    {values.includeType && (
                      <div className="flex items-center gap-3">
                        <span className="text-xs w-20 text-muted-foreground">Corporate</span>
                        <code className="text-sm font-mono text-foreground">{previewCorp}</code>
                      </div>
                    )}
                  </div>
                  {config && (
                    <p className="text-xs text-muted-foreground pt-1">
                      Sequences issued so far —{' '}
                      {values.includeType
                        ? `Individual: ${config.lastSequenceIndividual.toLocaleString()}, Corporate: ${config.lastSequenceCorporate.toLocaleString()}`
                        : `Shared: ${config.lastSequence.toLocaleString()}`}
                    </p>
                  )}
                </div>

                {save.isError && (
                  <p className="text-xs text-destructive rounded border border-destructive/30 bg-destructive/10 px-3 py-2">
                    Failed to save. Please check your settings and try again.
                  </p>
                )}

                <div className="flex justify-end">
                  <Button type="submit" disabled={save.isPending}>
                    {save.isPending ? 'Saving…' : config ? 'Update Format' : 'Save Format'}
                  </Button>
                </div>

              </form>
            </Form>
          )}
        </CardContent>
      </Card>

      {!config && !isLoading && (
        <p className="text-xs text-amber-600 rounded border border-amber-200 bg-amber-50 px-3 py-2">
          No customer number format is configured yet. Customer onboarding will fail until a format is saved.
        </p>
      )}
    </div>
  );
}
