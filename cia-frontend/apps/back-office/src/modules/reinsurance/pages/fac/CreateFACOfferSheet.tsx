import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
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
  reinsurerId:       z.string().min(1, 'Select a reinsurer'),
  facPremiumRate:    z.coerce.number().min(0).max(100),
  commissionRate:    z.coerce.number().min(0).max(100),
  offerValidUntil:   z.string().min(1, 'Required'),
  coverStartDate:    z.string().min(1, 'Required'),
  coverEndDate:      z.string().min(1, 'Required'),
  notes:             z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

// Placeholder — replace with hooks when backend is wired
const EXCESS_POLICIES = [
  { id: 'pol-ex1', label: 'POL-2026-00005 — Alaba Trading · Fire & Burglary · SI ₦35,000,000 (treaty cap ₦9M)' },
  { id: 'pol-ex2', label: 'POL-2026-00006 — Dangote Group · Engineering · SI ₦60,000,000 (treaty cap ₦10M)' },
];

const REINSURERS = [
  { id: 'r1', name: 'Munich Re' },
  { id: 'r2', name: 'Swiss Re' },
  { id: 'r3', name: 'African Re' },
  { id: 'r4', name: "Lloyd's Syndicate 2623" },
  { id: 'r5', name: 'ZEP-RE (PTA Reinsurance)' },
  { id: 'r6', name: 'Continental Reinsurance' },
];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CreateFACOfferSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      policyId: '', riskDescription: '', totalSumInsured: 0, treatyRetention: 0,
      facSumInsured: 0, reinsurerId: '', facPremiumRate: 0, commissionRate: 0,
      offerValidUntil: '', coverStartDate: '', coverEndDate: '', notes: '',
    },
  });

  const totalSI    = form.watch('totalSumInsured')  || 0;
  const retention  = form.watch('treatyRetention')  || 0;
  const facSI      = form.watch('facSumInsured')     || 0;
  const facRate    = form.watch('facPremiumRate')    || 0;
  const commRate   = form.watch('commissionRate')    || 0;
  const facPremium = (facSI * facRate) / 100;
  const netPremium = facPremium * (1 - commRate / 100);

  // Auto-compute FAC SI when retention changes
  function onRetentionChange(value: string, fieldOnChange: (v: string) => void) {
    fieldOnChange(value);
    const excess = totalSI - Number(value);
    if (excess > 0) form.setValue('facSumInsured', excess);
  }

  async function onSubmit(values: FormValues) {
    console.log('Create FAC Offer', values);
    // TODO: POST /api/v1/reinsurance/fac/outward
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Create FAC Offer</SheetTitle>
          <SheetDescription>
            Place the risk excess over treaty capacity with a reinsurer on a facultative basis.
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
                      <Input
                        type="number" min={0} step={1000000}
                        {...field}
                        onChange={(e) => onRetentionChange(e.target.value, field.onChange)}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="facSumInsured"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>FAC Sum Insured (₦)</FormLabel>
                    <FormControl><Input type="number" min={0} step={1000000} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            {/* Visual check */}
            {totalSI > 0 && (
              <div className="rounded-lg bg-muted/40 p-3 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Our retention</span>
                  <span className="font-medium">₦{retention.toLocaleString()} ({totalSI > 0 ? Math.round(retention/totalSI*100) : 0}%)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">FAC cession</span>
                  <span className="font-medium text-primary">₦{facSI.toLocaleString()} ({totalSI > 0 ? Math.round(facSI/totalSI*100) : 0}%)</span>
                </div>
              </div>
            )}

            <Separator />
            <p className="text-sm font-semibold text-foreground">FAC Terms</p>

            <FormField control={form.control} name="reinsurerId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Reinsurer</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select reinsurer" /></SelectTrigger></FormControl>
                    <SelectContent>{REINSURERS.map(r => <SelectItem key={r.id} value={r.id}>{r.name}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormRow>
              <FormField control={form.control} name="facPremiumRate"
                render={({ field }) => (<FormItem><FormLabel>FAC Premium Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="commissionRate"
                render={({ field }) => (<FormItem><FormLabel>Reinsurer Commission (%)</FormLabel><FormControl><Input type="number" min={0} max={50} step={0.5} {...field} /></FormControl><FormMessage /></FormItem>)} />
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
                    <span className="text-muted-foreground">Commission ({commRate}%)</span>
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
              render={({ field }) => (<FormItem><FormLabel>Notes (optional)</FormLabel><FormControl><Input placeholder="Any special conditions or remarks" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Sending…' : 'Send FAC Offer'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
