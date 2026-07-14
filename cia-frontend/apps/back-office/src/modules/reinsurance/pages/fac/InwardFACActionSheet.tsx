import { useEffect } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Separator,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  toast,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { validatedPost, FacInwardDtoSchema, type FacInwardDto } from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';
import { formatNaira } from '@/lib/format';

export type InwardFACMode = 'RENEW' | 'EXTEND';

// RENEW: carries over premium terms from the source cover — only the new
// period is collected. Mirrors RenewFacInwardRequest (coverFrom, coverTo);
// the backend rejects an inverted period with INVALID_COVER_PERIOD.
const renewSchema = z.object({
  coverFrom: z.string().min(1, 'Required'),
  coverTo:   z.string().min(1, 'Required'),
}).refine((data) => !data.coverFrom || !data.coverTo || data.coverTo > data.coverFrom, {
  message: 'Cover end must be after cover start',
  path:    ['coverTo'],
});
type RenewFormValues = z.infer<typeof renewSchema>;

// EXTEND: single new end date, strictly after the cover's current coverTo.
// Mirrors ExtendFacInwardRequest (newCoverTo only).
function buildExtendSchema(currentCoverTo: string) {
  return z.object({
    newCoverTo: z.string().min(1, 'Required'),
  }).refine((data) => !data.newCoverTo || data.newCoverTo > currentCoverTo, {
    message: 'New cover end must be after the current cover end',
    path:    ['newCoverTo'],
  });
}
type ExtendFormValues = { newCoverTo: string };

function addDays(dateStr: string, days: number): string {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}

function addYears(dateStr: string, years: number): string {
  const d = new Date(dateStr);
  d.setFullYear(d.getFullYear() + years);
  return d.toISOString().split('T')[0];
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-1.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-xs text-muted-foreground">{label}</p>
      <p className="text-xs font-medium text-foreground">{value}</p>
    </div>
  );
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  fac:          FacInwardDto | null;
  mode:         InwardFACMode;
  onSuccess:    () => void;
}

export default function InwardFACActionSheet({ open, onOpenChange, fac, mode, onSuccess }: Props) {
  const isRenew = mode === 'RENEW';
  const queryClient = useQueryClient();

  const renewForm = useForm<RenewFormValues>({
    resolver:      zodResolver(renewSchema),
    defaultValues: { coverFrom: '', coverTo: '' },
  });

  const extendForm = useForm<ExtendFormValues>({
    resolver:      zodResolver(buildExtendSchema(fac?.coverTo ?? '')),
    defaultValues: { newCoverTo: '' },
  });

  useEffect(() => {
    if (open && fac) {
      renewForm.reset({
        coverFrom: addDays(fac.coverTo, 1),
        coverTo:   addYears(fac.coverTo, 1),
      });
      extendForm.reset({ newCoverTo: '' });
    }
  }, [open, fac?.id, mode]); // eslint-disable-line react-hooks/exhaustive-deps

  const renew = useMutation({
    mutationFn: async (values: RenewFormValues) => {
      if (!fac) throw new Error('No cover selected');
      return validatedPost(`/api/v1/ri/fac-inwards/${fac.id}/renew`, values, FacInwardDtoSchema);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'fac-inwards'] });
      toast({ title: 'Cover renewed' });
      renewForm.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, renewForm, { defaultTitle: 'Could not renew inward FAC cover' }),
  });

  const extend = useMutation({
    mutationFn: async (values: ExtendFormValues) => {
      if (!fac) throw new Error('No cover selected');
      return validatedPost(`/api/v1/ri/fac-inwards/${fac.id}/extend`, values, FacInwardDtoSchema);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'fac-inwards'] });
      toast({ title: 'Cover extended' });
      extendForm.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, extendForm, { defaultTitle: 'Could not extend inward FAC cover' }),
  });

  const action = isRenew ? renew : extend;

  function onRenewSubmit(values: RenewFormValues) {
    renew.mutate(values);
  }

  function onExtendSubmit(values: ExtendFormValues) {
    extend.mutate(values);
  }

  if (!fac) return null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{isRenew ? 'Renew Inward FAC Cover' : 'Extend Inward FAC Cover'}</SheetTitle>
          <SheetDescription>
            {isRenew
              ? 'Create a renewal for the next term, linked to this cover.'
              : "Extend this cover's period with incremental pro-rata premium for the additional days."}
          </SheetDescription>
        </SheetHeader>

        {/* Current cover summary */}
        <div className="mt-4 rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Current Cover — {fac.facInwardReference}
            </p>
          </div>
          <div className="px-4 pb-3">
            <DetailRow label="Ceding Company" value={fac.cedingCompanyName} />
            <DetailRow label="Class"          value={fac.classOfBusinessName} />
            <DetailRow label="Sum Insured"    value={formatNaira(fac.sumInsured)} />
            <DetailRow label="Our Share"      value={`${fac.ourSharePct}%`} />
            <DetailRow label="Gross Premium"  value={formatNaira(fac.grossPremium)} />
            <DetailRow label="Period"         value={`${fac.coverFrom} → ${fac.coverTo}`} />
          </div>
        </div>

        {isRenew ? (
          <Form {...renewForm}>
            <form onSubmit={renewForm.handleSubmit(onRenewSubmit)} className="mt-4 space-y-4">
              <Separator />
              <p className="text-sm font-semibold text-foreground">Renewal Period</p>

              <FormRow>
                <FormField control={renewForm.control} name="coverFrom"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Cover From</FormLabel>
                      <FormControl><Input type="date" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField control={renewForm.control} name="coverTo"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Cover To</FormLabel>
                      <FormControl><Input type="date" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </FormRow>

              <p className="text-xs text-muted-foreground">
                Premium terms (share %, rate, commission) carry over from the current cover.
              </p>

              <SheetFooter className="pt-2">
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
                <Button type="submit" disabled={action.isPending}>
                  {action.isPending ? 'Renewing…' : 'Confirm Renewal'}
                </Button>
              </SheetFooter>
            </form>
          </Form>
        ) : (
          <Form {...extendForm}>
            <form onSubmit={extendForm.handleSubmit(onExtendSubmit)} className="mt-4 space-y-4">
              <Separator />
              <p className="text-sm font-semibold text-foreground">Extension Period</p>

              <FormField control={extendForm.control} name="newCoverTo"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>New Cover To</FormLabel>
                    <FormControl><Input type="date" min={fac.coverTo} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <p className="text-xs text-muted-foreground">
                Extending adds incremental pro-rata premium for the additional days.
              </p>

              <SheetFooter className="pt-2">
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
                <Button type="submit" disabled={action.isPending}>
                  {action.isPending ? 'Extending…' : 'Confirm Extension'}
                </Button>
              </SheetFooter>
            </form>
          </Form>
        )}
      </SheetContent>
    </Sheet>
  );
}
