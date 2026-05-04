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
import { applyApiErrors } from '@/lib/form-errors';

interface PolicySummaryDto {
  id:            string;
  policyNumber:  string;
  customerName:  string;
  productName?:  string;
  status:        string;
}

// All 10 endorsement types
const ENDORSEMENT_TYPES = [
  { value: 'RENEWAL',       label: 'Renewal',                group: 'Period' },
  { value: 'EXTENSION',     label: 'Extension of Period',    group: 'Period' },
  { value: 'CANCELLATION',  label: 'Cancellation',           group: 'Period' },
  { value: 'REVERSAL',      label: 'Reversal',               group: 'Period' },
  { value: 'REDUCTION',     label: 'Reduction in Period',    group: 'Period' },
  { value: 'CHANGE_PERIOD', label: 'Change in Period',       group: 'Period' },
  { value: 'INCREASE_SI',   label: 'Increase Sum Insured',   group: 'Coverage' },
  { value: 'DECREASE_SI',   label: 'Decrease Sum Insured',   group: 'Coverage' },
  { value: 'ADD_ITEMS',     label: 'Add Insured Items',      group: 'Coverage' },
  { value: 'DELETE_ITEMS',  label: 'Delete Insured Items',   group: 'Coverage' },
] as const;

type EndorsementType = typeof ENDORSEMENT_TYPES[number]['value'];

const baseSchema = z.object({
  policyId:         z.string().min(1, 'Select a policy'),
  endorsementType:  z.string().min(1, 'Select endorsement type'),
  effectiveDate:    z.string().min(1, 'Required'),
  reason:           z.string().min(5, 'Provide a reason'),
  // Period changes
  newStartDate:     z.string().optional(),
  newEndDate:       z.string().optional(),
  // SI changes
  newSumInsured:    z.coerce.number().min(0).optional(),
  // Item changes
  itemDescription:  z.string().optional(),
});
type FormValues = z.infer<typeof baseSchema>;

// Pro-rata premium preview (days remaining × daily rate)
function calcProRata(annualPremium: number, daysAffected: number): number {
  return Math.round((annualPremium / 365) * daysAffected);
}

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CreateEndorsementSheet({ open, onOpenChange, onSuccess }: Props) {
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
    resolver:      zodResolver(baseSchema) as any,
    defaultValues: {
      policyId: '', endorsementType: '', effectiveDate: '', reason: '',
      newStartDate: '', newEndDate: '', newSumInsured: 0, itemDescription: '',
    },
  });

  const endorsementType = form.watch('endorsementType') as EndorsementType | '';
  const newSI          = form.watch('newSumInsured') || 0;

  // Determine which extra fields to show
  const showPeriodFields  = ['RENEWAL','EXTENSION','REDUCTION','CHANGE_PERIOD'].includes(endorsementType);
  const showSIFields      = ['INCREASE_SI','DECREASE_SI'].includes(endorsementType);
  const showItemFields    = ['ADD_ITEMS','DELETE_ITEMS'].includes(endorsementType);
  const showCancelFields  = endorsementType === 'CANCELLATION';
  const showReversalNote  = endorsementType === 'REVERSAL';

  // Indicative pro-rata (simplified: assume 180 days affected at 2.25% rate)
  const indicativePremium = showSIFields && newSI > 0 ? calcProRata(newSI * 0.0225, 180) : null;

  const create = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>('/api/v1/endorsements', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['endorsements'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not create endorsement' }),
  });

  function onSubmit(values: FormValues) {
    create.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Endorsement</SheetTitle>
          <SheetDescription>
            Select the endorsement type — the form adapts to show the relevant fields.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            {/* Policy + Type — always shown */}
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

            <FormField control={form.control} name="endorsementType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Endorsement Type</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="RENEWAL">Renewal</SelectItem>
                      <SelectItem value="EXTENSION">Extension of Period</SelectItem>
                      <SelectItem value="CANCELLATION">Cancellation</SelectItem>
                      <SelectItem value="REVERSAL">Reversal</SelectItem>
                      <SelectItem value="REDUCTION">Reduction in Period</SelectItem>
                      <SelectItem value="CHANGE_PERIOD">Change in Period</SelectItem>
                      <SelectItem value="INCREASE_SI">Increase Sum Insured</SelectItem>
                      <SelectItem value="DECREASE_SI">Decrease Sum Insured</SelectItem>
                      <SelectItem value="ADD_ITEMS">Add Insured Items</SelectItem>
                      <SelectItem value="DELETE_ITEMS">Delete Insured Items</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="effectiveDate"
              render={({ field }) => (<FormItem><FormLabel>Effective Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />

            {/* ── Period change fields ──────────────────────────────────── */}
            {showPeriodFields && (
              <>
                <Separator />
                <p className="text-sm font-semibold text-foreground">New Policy Period</p>
                <FormRow>
                  <FormField control={form.control} name="newStartDate"
                    render={({ field }) => (<FormItem><FormLabel>New Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
                  <FormField control={form.control} name="newEndDate"
                    render={({ field }) => (<FormItem><FormLabel>New End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
                </FormRow>
              </>
            )}

            {/* ── SI change fields ──────────────────────────────────────── */}
            {showSIFields && (
              <>
                <Separator />
                <p className="text-sm font-semibold text-foreground">Sum Insured Change</p>
                <FormField control={form.control} name="newSumInsured"
                  render={({ field }) => (<FormItem><FormLabel>New Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={100000} {...field} /></FormControl><FormMessage /></FormItem>)} />
                {indicativePremium !== null && (
                  <div className="rounded-lg border bg-muted/40 p-3 space-y-1">
                    <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Pro-rata Premium (indicative)</p>
                    <div className="flex justify-between text-sm font-semibold">
                      <span>180 days × daily rate</span>
                      <span className={`${endorsementType === 'DECREASE_SI' ? 'text-destructive' : 'text-primary'}`}>
                        {endorsementType === 'DECREASE_SI' ? '−' : ''}₦{indicativePremium.toLocaleString()}
                      </span>
                    </div>
                    <p className="text-xs text-muted-foreground">Final calculation computed on approval.</p>
                  </div>
                )}
              </>
            )}

            {/* ── Cancellation fields ───────────────────────────────────── */}
            {showCancelFields && (
              <>
                <Separator />
                <p className="text-sm font-semibold text-foreground">Cancellation Details</p>
                <div className="rounded-lg border bg-[var(--status-rejected-bg)] p-3">
                  <p className="text-xs font-medium text-[var(--status-rejected-fg)]">
                    A pro-rata refund will be calculated based on the unexpired period from the effective date.
                  </p>
                </div>
              </>
            )}

            {/* ── Reversal note ─────────────────────────────────────────── */}
            {showReversalNote && (
              <>
                <Separator />
                <div className="rounded-lg border bg-[var(--status-pending-bg)] p-3">
                  <p className="text-xs font-medium text-[var(--status-pending-fg)]">
                    A reversal will undo the most recent endorsement on this policy. The original policy terms will be restored.
                  </p>
                </div>
              </>
            )}

            {/* ── Item change fields ────────────────────────────────────── */}
            {showItemFields && (
              <>
                <Separator />
                <p className="text-sm font-semibold text-foreground">
                  {endorsementType === 'ADD_ITEMS' ? 'Items to Add' : 'Items to Remove'}
                </p>
                <FormField control={form.control} name="itemDescription"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Item Description</FormLabel>
                      <FormControl>
                        <Input
                          placeholder={endorsementType === 'ADD_ITEMS'
                            ? 'e.g. 2024 Honda Accord, Reg: EKY-002-BB'
                            : 'e.g. 2019 Toyota Camry, Reg: LND-001-AA'}
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </>
            )}

            <Separator />

            <FormField control={form.control} name="reason"
              render={({ field }) => (<FormItem><FormLabel>Reason / Notes</FormLabel><FormControl><Input placeholder="Reason for endorsement" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={create.isPending || !endorsementType}>
                {create.isPending ? 'Saving…' : 'Save as Draft'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
