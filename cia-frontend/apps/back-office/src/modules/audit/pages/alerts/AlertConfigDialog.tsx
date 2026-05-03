import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Separator, Switch,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';

const schema = z.object({
  failedLoginThreshold:   z.coerce.number().int().min(1).max(20),
  bulkDeleteThreshold:    z.coerce.number().int().min(1).max(50),
  largeApprovalThreshold: z.coerce.number().positive(),
  businessHoursStart:     z.string().min(1, 'Required'),
  businessHoursEnd:       z.string().min(1, 'Required'),
  retentionYears:         z.coerce.number().int().min(1).max(30),
  alertEmailEnabled:      z.boolean(),
  alertEmailRecipients:   z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

const DEFAULTS: FormValues = {
  failedLoginThreshold:   3,
  bulkDeleteThreshold:    5,
  largeApprovalThreshold: 50_000_000,
  businessHoursStart:     '09:00',
  businessHoursEnd:       '17:00',
  retentionYears:         7,
  alertEmailEnabled:      true,
  alertEmailRecipients:   'akinwale@nubeero.com',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
}

export default function AlertConfigDialog({ open, onOpenChange }: Props) {
  const queryClient = useQueryClient();

  const configQuery = useQuery<FormValues>({
    queryKey: ['audit', 'alert-config'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: FormValues }>('/api/v1/audit/alert-config');
      return res.data.data;
    },
    enabled: open,
  });

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: DEFAULTS,
  });

  useEffect(() => {
    if (configQuery.data) form.reset(configQuery.data);
  }, [configQuery.data, form]);

  const emailEnabled = form.watch('alertEmailEnabled');

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.put<{ data: FormValues }>('/api/v1/audit/alert-config', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['audit', 'alert-config'] });
      onOpenChange(false);
    },
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) form.reset(DEFAULTS); onOpenChange(v); }}>
      <DialogContent className="sm:max-w-md overflow-y-auto max-h-[90vh]">
        <DialogHeader>
          <DialogTitle>Alert Configuration</DialogTitle>
          <DialogDescription>
            Configure the thresholds and notification settings for real-time audit alerts.
            Changes apply to this tenant only.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">

            <p className="text-sm font-semibold text-foreground">Detection Thresholds</p>

            <FormRow>
              <FormField control={form.control} name="failedLoginThreshold"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Failed Logins</FormLabel>
                    <FormControl><Input type="number" min={1} max={20} {...field} /></FormControl>
                    <FormDescription className="text-xs">Trigger after N consecutive failures</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="bulkDeleteThreshold"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Bulk Deletes</FormLabel>
                    <FormControl><Input type="number" min={1} max={50} {...field} /></FormControl>
                    <FormDescription className="text-xs">Trigger after N deletes in 5 min</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormField control={form.control} name="largeApprovalThreshold"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Large Approval Threshold (₦)</FormLabel>
                  <FormControl><Input type="number" min={0} step={1_000_000} {...field} /></FormControl>
                  <FormDescription className="text-xs">Alert when a single approval exceeds this amount</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Separator />
            <p className="text-sm font-semibold text-foreground">Business Hours</p>
            <p className="text-xs text-muted-foreground">Activity outside these hours triggers off-hours alerts.</p>

            <FormRow>
              <FormField control={form.control} name="businessHoursStart"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Start</FormLabel>
                    <FormControl><Input type="time" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="businessHoursEnd"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>End</FormLabel>
                    <FormControl><Input type="time" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <Separator />
            <p className="text-sm font-semibold text-foreground">Retention</p>

            <FormField control={form.control} name="retentionYears"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Audit Log Retention (years)</FormLabel>
                  <FormControl><Input type="number" min={1} max={30} {...field} /></FormControl>
                  <FormDescription className="text-xs">NDPR minimum is 2 years; default is 7 years</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Separator />
            <p className="text-sm font-semibold text-foreground">Notifications</p>

            <FormField control={form.control} name="alertEmailEnabled"
              render={({ field }) => (
                <FormItem className="flex items-center justify-between rounded-lg border p-3">
                  <div>
                    <FormLabel className="text-sm">Email Alerts</FormLabel>
                    <FormDescription className="text-xs mt-0.5">Send alert notifications by email</FormDescription>
                  </div>
                  <FormControl>
                    <Switch checked={field.value} onCheckedChange={field.onChange} />
                  </FormControl>
                </FormItem>
              )}
            />

            {emailEnabled && (
              <FormField control={form.control} name="alertEmailRecipients"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Email Recipients</FormLabel>
                    <FormControl>
                      <Input placeholder="admin@company.com, ciso@company.com" {...field} />
                    </FormControl>
                    <FormDescription className="text-xs">Comma-separated email addresses</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { form.reset(DEFAULTS); onOpenChange(false); }}>
                Cancel
              </Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : 'Save Configuration'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
