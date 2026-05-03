import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormDescription, FormField,
  FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type BrokerDto } from '@cia/api-client';
import { z } from 'zod';

interface ReinsurerDto { id: string; name: string; }
interface PolicyExcessDto {
  id:                string;
  policyNumber:      string;
  customerName:      string;
  classOfBusinessName?: string;
  sumInsured:        number;
  treatyCapacity?:   number;
}

const schema = z.object({
  policyId:          z.string().min(1, 'Select the policy requiring FAC cover'),
  riskDescription:   z.string().min(5, 'Describe the risk'),
  totalSumInsured:   z.coerce.number().positive(),
  treatyRetention:   z.coerce.number().min(0),
  facSumInsured:     z.coerce.number().positive('FAC SI must be positive'),
  // Placement method
  placedThrough:     z.enum(['DIRECT', 'BROKER']),
  counterpartyId:    z.string().min(1, 'Required'),
  brokerMarkets:     z.string().optional(),   // only when BROKER — describe the markets
  facPremiumRate:    z.coerce.number().min(0).max(100),
  commissionRate:    z.coerce.number().min(0).max(100),
  offerValidUntil:   z.string().min(1, 'Required'),
  coverStartDate:    z.string().min(1, 'Required'),
  coverEndDate:      z.string().min(1, 'Required'),
  notes:             z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CreateFACOfferSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const excessPoliciesQuery = useQuery<PolicyExcessDto[]>({
    queryKey: ['reinsurance', 'fac', 'excess-policies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PolicyExcessDto[] }>(
        '/api/v1/reinsurance/fac/excess-policies',
      );
      return res.data.data;
    },
    enabled: open,
  });
  const excessPolicies = (excessPoliciesQuery.data ?? []).map(p => ({
    id:    p.id,
    label: `${p.policyNumber} — ${p.customerName}${p.classOfBusinessName ? ` · ${p.classOfBusinessName}` : ''} · SI ₦${p.sumInsured.toLocaleString()}${p.treatyCapacity ? ` (treaty cap ₦${p.treatyCapacity.toLocaleString()})` : ''}`,
  }));

  const reinsurersQuery = useQuery<ReinsurerDto[]>({
    queryKey: ['setup', 'reinsurance-companies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReinsurerDto[] }>('/api/v1/setup/reinsurance-companies');
      return res.data.data;
    },
    enabled: open,
  });
  const reinsurers = reinsurersQuery.data ?? [];

  const brokersQuery = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BrokerDto[] }>('/api/v1/setup/brokers');
      return res.data.data;
    },
    enabled: open,
  });
  const facBrokers = (brokersQuery.data ?? []).map(b => ({ id: b.id, name: b.name }));

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      policyId: '', riskDescription: '', totalSumInsured: 0, treatyRetention: 0,
      facSumInsured: 0, placedThrough: 'DIRECT', counterpartyId: '', brokerMarkets: '',
      facPremiumRate: 0, commissionRate: 0, offerValidUntil: '', coverStartDate: '', coverEndDate: '', notes: '',
    },
  });

  const placedThrough = form.watch('placedThrough');
  const totalSI       = form.watch('totalSumInsured') || 0;
  const retention     = form.watch('treatyRetention') || 0;
  const facSI         = form.watch('facSumInsured')    || 0;
  const facRate       = form.watch('facPremiumRate')   || 0;
  const commRate      = form.watch('commissionRate')   || 0;
  const facPremium    = (facSI * facRate) / 100;
  const netPremium    = facPremium * (1 - commRate / 100);

  function onRetentionChange(value: string, fieldOnChange: (v: string) => void) {
    fieldOnChange(value);
    const excess = totalSI - Number(value);
    if (excess > 0) form.setValue('facSumInsured', excess);
  }

  function onPlacedThroughChange(type: 'DIRECT' | 'BROKER') {
    form.setValue('placedThrough', type);
    form.setValue('counterpartyId', '');   // clear selection when switching
    form.setValue('brokerMarkets', '');
  }

  const create = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        '/api/v1/reinsurance/fac/outward', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reinsurance', 'fac'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: FormValues) {
    create.mutate(values);
  }

  const isBroker = placedThrough === 'BROKER';
  const counterparties: { id: string; name: string }[] = isBroker ? facBrokers : reinsurers;
  const counterpartyLabel = isBroker ? 'FAC Broker' : 'Reinsurer';
  const counterpartyPlaceholder = isBroker ? 'Select FAC broker' : 'Select reinsurer';

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Create FAC Offer</SheetTitle>
          <SheetDescription>
            Place the risk excess over treaty capacity on a facultative basis — directly with a reinsurer or through a FAC broker.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            {/* Policy */}
            <FormField control={form.control} name="policyId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Policy (Excess Capacity)</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select policy needing FAC cover" /></SelectTrigger></FormControl>
                    <SelectContent>{excessPolicies.map(p => <SelectItem key={p.id} value={p.id}>{p.label}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="riskDescription"
              render={({ field }) => (<FormItem><FormLabel>Risk Description</FormLabel><FormControl><Input placeholder="Describe the risk being ceded" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <Separator />
            <p className="text-sm font-semibold text-foreground">Sum Insured Split</p>

            <FormField control={form.control} name="totalSumInsured"
              render={({ field }) => (<FormItem><FormLabel>Total Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={1000000} {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="treatyRetention"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Treaty Retention (₦)</FormLabel>
                    <FormControl>
                      <Input type="number" min={0} step={1000000} {...field}
                        onChange={(e) => onRetentionChange(e.target.value, field.onChange)} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="facSumInsured"
                render={({ field }) => (<FormItem><FormLabel>FAC Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={1000000} {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            {totalSI > 0 && (
              <div className="rounded-lg bg-muted/40 p-3 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Our retention</span>
                  <span className="font-medium">₦{retention.toLocaleString()} ({totalSI > 0 ? Math.round(retention / totalSI * 100) : 0}%)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">FAC cession</span>
                  <span className="font-medium text-primary">₦{facSI.toLocaleString()} ({totalSI > 0 ? Math.round(facSI / totalSI * 100) : 0}%)</span>
                </div>
              </div>
            )}

            <Separator />
            <p className="text-sm font-semibold text-foreground">Placement Method</p>

            {/* ── Placed Through toggle ──────────────────────────────────── */}
            <div className="grid grid-cols-2 gap-3">
              {(['DIRECT', 'BROKER'] as const).map((type) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => onPlacedThroughChange(type)}
                  className={`rounded-lg border p-3 text-left transition-colors ${
                    placedThrough === type
                      ? 'bg-teal-50 border-primary'
                      : 'bg-card hover:bg-secondary'
                  }`}
                >
                  <p className="text-sm font-semibold text-foreground">
                    {type === 'DIRECT' ? 'Direct with Reinsurer' : 'Through FAC Broker'}
                  </p>
                  <p className="text-xs text-muted-foreground mt-1">
                    {type === 'DIRECT'
                      ? 'Offer sent directly to the reinsurer — Munich Re, Lloyd\'s, African Re, etc.'
                      : 'FAC broker arranges the placement — Marsh Re, Aon Re, SCIB, etc.'}
                  </p>
                </button>
              ))}
            </div>

            {/* Counterparty select — label + options change based on toggle */}
            <FormField control={form.control} name="counterpartyId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{counterpartyLabel}</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl>
                      <SelectTrigger><SelectValue placeholder={counterpartyPlaceholder} /></SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {counterparties.map(c => (
                        <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Broker markets note — only shown when BROKER */}
            {isBroker && (
              <FormField control={form.control} name="brokerMarkets"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Target Markets (optional)</FormLabel>
                    <FormControl>
                      <Input
                        placeholder="e.g. Lloyd's, Munich Re, Swiss Re — broker will approach these markets"
                        {...field}
                      />
                    </FormControl>
                    <FormDescription className="text-xs">
                      The broker will approach these markets on your behalf. Leave blank to let the broker decide.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <Separator />
            <p className="text-sm font-semibold text-foreground">FAC Terms</p>

            <FormRow>
              <FormField control={form.control} name="facPremiumRate"
                render={({ field }) => (<FormItem><FormLabel>FAC Premium Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="commissionRate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>{isBroker ? 'Brokerage (%)' : 'Reinsurer Commission (%)'}</FormLabel>
                    <FormControl><Input type="number" min={0} max={50} step={0.5} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            {facSI > 0 && facRate > 0 && (
              <div className="rounded-lg border bg-muted/40 p-3 space-y-1">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">FAC Premium Summary</p>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Gross FAC Premium</span>
                  <span className="font-medium">₦{facPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                {commRate > 0 && (
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">{isBroker ? 'Brokerage' : 'Commission'} ({commRate}%)</span>
                    <span className="text-destructive">−₦{(facPremium - netPremium).toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                <Separator />
                <div className="flex justify-between text-sm font-semibold">
                  <span>Net FAC Premium</span>
                  <span className="text-primary">₦{netPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
              </div>
            )}

            <Separator />

            <FormField control={form.control} name="offerValidUntil"
              render={({ field }) => (<FormItem><FormLabel>Offer Valid Until</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="coverStartDate"
                render={({ field }) => (<FormItem><FormLabel>Cover Start</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="coverEndDate"
                render={({ field }) => (<FormItem><FormLabel>Cover End</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="notes"
              render={({ field }) => (<FormItem><FormLabel>Notes (optional)</FormLabel><FormControl><Input placeholder="Special conditions or instructions to the broker/reinsurer" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Sending…' : isBroker ? 'Send to Broker' : 'Send FAC Offer'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
