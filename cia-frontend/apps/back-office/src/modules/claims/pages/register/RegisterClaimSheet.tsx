import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';

interface PolicySummaryDto {
  id:           string;
  policyNumber: string;
  customerName: string;
  productName?: string;
}

const schema = z.object({
  policyId:        z.string().min(1, 'Select the insured policy'),
  incidentDate:    z.string().min(1, 'Required'),
  notificationDate:z.string().min(1, 'Required'),
  natureOfLoss:    z.string().min(1, 'Select nature of loss'),
  causeOfLoss:     z.string().min(1, 'Select cause of loss'),
  description:     z.string().min(10, 'Describe what happened (min 10 characters)'),
  location:        z.string().min(5, 'Required'),
  estimatedLoss:   z.coerce.number().positive('Must be positive'),
  contactName:     z.string().min(2, 'Required'),
  contactPhone:    z.string().min(7, 'Required'),
});
type FormValues = z.infer<typeof schema>;

const NATURES = ['Own Damage', 'Third Party Bodily Injury', 'Third Party Property Damage', 'Fire', 'Theft', 'Flood', 'Explosion', 'Storm'];
const CAUSES  = ['Accident', 'Fire Outbreak', 'Theft / Burglary', 'Natural Disaster', 'Malicious Damage', 'Mechanical Failure', 'Human Error'];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function RegisterClaimSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const policiesQuery = useQuery<PolicySummaryDto[]>({
    queryKey: ['policies', { status: 'ACTIVE' }],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PolicySummaryDto[] }>('/api/v1/policies', {
        params: { status: 'ACTIVE' },
      });
      return res.data.data;
    },
    enabled: open,
  });
  const policies = (policiesQuery.data ?? []).map(p => ({
    id:    p.id,
    label: `${p.policyNumber} — ${p.customerName}${p.productName ? ` · ${p.productName}` : ''}`,
  }));

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { policyId: '', incidentDate: '', notificationDate: '', natureOfLoss: '', causeOfLoss: '', description: '', location: '', estimatedLoss: 0, contactName: '', contactPhone: '' },
  });

  const register = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>('/api/v1/claims', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: FormValues) {
    register.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Register Claim</SheetTitle>
          <SheetDescription>
            Record the first notification of loss and initial claim details.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            {/* Policy */}
            <FormField control={form.control} name="policyId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Policy</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select active policy" /></SelectTrigger></FormControl>
                    <SelectContent>{policies.map(p => <SelectItem key={p.id} value={p.id}>{p.label}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Dates */}
            <FormRow>
              <FormField control={form.control} name="incidentDate"
                render={({ field }) => (<FormItem><FormLabel>Incident Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="notificationDate"
                render={({ field }) => (<FormItem><FormLabel>Notification Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <Separator />
            <p className="text-sm font-semibold text-foreground">Loss Details</p>

            {/* Nature / Cause */}
            <FormRow>
              <FormField control={form.control} name="natureOfLoss"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Nature of Loss</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{NATURES.map(n => <SelectItem key={n} value={n}>{n}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="causeOfLoss"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Cause of Loss</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{CAUSES.map(c => <SelectItem key={c} value={c}>{c}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormField control={form.control} name="location"
              render={({ field }) => (<FormItem><FormLabel>Incident Location</FormLabel><FormControl><Input placeholder="Street, area, city" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormField control={form.control} name="description"
              render={({ field }) => (<FormItem><FormLabel>Description of Incident</FormLabel><FormControl><Input placeholder="Describe what happened in detail" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormField control={form.control} name="estimatedLoss"
              render={({ field }) => (<FormItem><FormLabel>Estimated Loss (₦)</FormLabel><FormControl><Input type="number" min={0} step={10000} {...field} /></FormControl><FormMessage /></FormItem>)} />

            <Separator />
            <p className="text-sm font-semibold text-foreground">Contact Person</p>

            <FormRow>
              <FormField control={form.control} name="contactName"
                render={({ field }) => (<FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="Contact full name" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="contactPhone"
                render={({ field }) => (<FormItem><FormLabel>Phone</FormLabel><FormControl><Input placeholder="+234 800 000 0000" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Registering…' : 'Register Claim'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
