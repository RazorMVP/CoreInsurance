import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormDescription, FormField,
  FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

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

// ── Placeholder lists — replace with useList hooks when backend is wired ──
const EXCESS_POLICIES = [
  { id: 'pol-ex1', label: 'POL-2026-00005 — Alaba Trading · Fire & Burglary · SI ₦35,000,000 (treaty cap ₦9M)' },
  { id: 'pol-ex2', label: 'POL-2026-00006 — Dangote Group · Engineering · SI ₦60,000,000 (treaty cap ₦10M)' },
];

// Direct reinsurers — from Setup → Organisations → Reinsurance Companies
const REINSURERS = [
  { id: 'r1', name: 'Munich Re' },
  { id: 'r2', name: 'Swiss Re' },
  { id: 'r3', name: 'African Re' },
  { id: 'r4', name: "Lloyd's Syndicate 2623" },
  { id: 'r5', name: "Lloyd's Syndicate 510 (MIG)" },
  { id: 'r6', name: 'ZEP-RE (PTA Reinsurance)' },
  { id: 'r7', name: 'Continental Reinsurance' },
  { id: 'r8', name: 'GIC Re (India)' },
  { id: 'r9', name: 'Trans-Atlantic Reinsurance' },
];

// FAC brokers — from Setup → Organisations → Brokers (reinsurance brokers subset)
const FAC_BROKERS = [
  { id: 'b1', name: 'Marsh (Nigeria) Ltd' },
  { id: 'b2', name: 'Aon Re Nigeria' },
  { id: 'b3', name: 'Willis Towers Watson Nigeria' },
  { id: 'b4', name: 'SCIB Nigeria' },
  { id: 'b5', name: 'Gras Savoye Willis Nigeria' },
  { id: 'b6', name: 'Brokerage International Ltd' },
  { id: 'b7', name: 'Anchor Insurance Brokers' },
];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CreateFACOfferSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
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

  async function onSubmit(values: FormValues) {
    console.log('Create FAC Offer', values);
    // TODO: POST /api/v1/reinsurance/fac/outward
    onSuccess();
  }

  const isBroker = placedThrough === 'BROKER';
  const counterparties = isBroker ? FAC_BROKERS : REINSURERS;
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
                    <SelectContent>{EXCESS_POLICIES.map(p => <SelectItem key={p.id} value={p.id}>{p.label}</SelectItem>)}</SelectContent>
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
