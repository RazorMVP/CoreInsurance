import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator, Skeleton, toast,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient, validatedPost, FacInwardDtoSchema,
  type ClassOfBusinessDto, type InsuranceCompanyDto,
} from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';
import { formatNaira } from '@/lib/format';

// Mirrors the backend CreateFacInwardRequest bean-validation bounds
// (cia-reinsurance/dto/CreateFacInwardRequest.java): sumInsured > 0,
// 0 < ourSharePct <= 100, premiumRate > 0, commissionRate optional >= 0,
// coverTo strictly after coverFrom (service throws INVALID_COVER_PERIOD).
const schema = z.object({
  cedingCompanyId:   z.string().min(1, 'Select the ceding company'),
  classOfBusinessId: z.string().min(1, 'Select class of business'),
  riskDescription:   z.string().optional(),
  sumInsured:        z.coerce.number().positive('Sum insured must be positive'),
  ourSharePct:       z.coerce.number().min(0.01, 'Min 0.01%').max(100, 'Max 100%'),
  premiumRate:       z.coerce.number().positive('Premium rate must be positive'),
  commissionRate:    z.coerce.number().min(0, 'Cannot be negative').optional(),
  coverFrom:         z.string().min(1, 'Required'),
  coverTo:           z.string().min(1, 'Required'),
}).refine((data) => !data.coverFrom || !data.coverTo || data.coverTo > data.coverFrom, {
  message: 'Cover end must be after cover start',
  path:    ['coverTo'],
});
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function AddInwardFACSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const cedingCompaniesQuery = useQuery<InsuranceCompanyDto[]>({
    queryKey: ['setup', 'insurance-companies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: InsuranceCompanyDto[] }>('/api/v1/setup/insurance-companies');
      return res.data.data;
    },
    enabled: open,
  });
  const cedingCompanies = cedingCompaniesQuery.data ?? [];

  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>('/api/v1/setup/classes-of-business');
      return res.data.data;
    },
    enabled: open,
  });
  const classes = classesQuery.data ?? [];

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      cedingCompanyId: '', classOfBusinessId: '', riskDescription: '',
      sumInsured: 0, ourSharePct: 0, premiumRate: 0, commissionRate: 0,
      coverFrom: '', coverTo: '',
    },
  });

  const sumInsured     = form.watch('sumInsured')     || 0;
  const ourSharePct    = form.watch('ourSharePct')    || 0;
  const premiumRate    = form.watch('premiumRate')    || 0;
  const commissionRate = form.watch('commissionRate') || 0;

  const acceptedSumInsured = sumInsured * ourSharePct / 100;
  const grossPremium       = acceptedSumInsured * premiumRate / 100;
  const commissionAmount   = grossPremium * commissionRate / 100;
  const netPremium         = grossPremium - commissionAmount;

  const create = useMutation({
    mutationFn: async (values: FormValues) => {
      const { commissionRate: cr, riskDescription, ...rest } = values;
      return validatedPost('/api/v1/ri/fac-inwards', {
        ...rest,
        riskDescription: riskDescription || undefined,
        commissionRate:  cr || undefined,
      }, FacInwardDtoSchema);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'fac-inwards'] });
      toast({ title: 'Inward FAC cover created' });
      form.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not create inward FAC cover' }),
  });

  function onSubmit(values: FormValues) {
    create.mutate(values);
  }

  const loadingMasterData = cedingCompaniesQuery.isLoading || classesQuery.isLoading;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Add Inward FAC Cover</SheetTitle>
          <SheetDescription>
            Accept a facultative share of a risk ceded by another insurance company. We become the reinsurer.
          </SheetDescription>
        </SheetHeader>

        {loadingMasterData ? (
          <div className="mt-6 space-y-3">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : (
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
              <FormField control={form.control} name="cedingCompanyId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Ceding Company</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select company" /></SelectTrigger></FormControl>
                      <SelectContent>{cedingCompanies.map(c => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField control={form.control} name="classOfBusinessId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Class of Business</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select class" /></SelectTrigger></FormControl>
                      <SelectContent>{classes.map(c => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField control={form.control} name="riskDescription"
                render={({ field }) => (<FormItem><FormLabel>Risk Description (optional)</FormLabel><FormControl><Input placeholder="Describe the insured risk" {...field} /></FormControl><FormMessage /></FormItem>)} />

              <Separator />
              <p className="text-sm font-semibold text-foreground">Our Participation</p>

              <FormField control={form.control} name="sumInsured"
                render={({ field }) => (<FormItem><FormLabel>Total Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={1000000} {...field} /></FormControl><FormMessage /></FormItem>)} />

              <FormRow>
                <FormField control={form.control} name="ourSharePct"
                  render={({ field }) => (<FormItem><FormLabel>Our Share (%)</FormLabel><FormControl><Input type="number" min={0.01} max={100} step={1} {...field} /></FormControl><FormMessage /></FormItem>)} />
                <FormField control={form.control} name="premiumRate"
                  render={({ field }) => (<FormItem><FormLabel>Premium Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
              </FormRow>

              <FormField control={form.control} name="commissionRate"
                render={({ field }) => (<FormItem><FormLabel>Ceding Commission We Pay (%, optional)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.5} {...field} /></FormControl><FormMessage /></FormItem>)} />

              {/* Premium preview — same formulas as RiFacInwardService.computeAmounts */}
              {acceptedSumInsured > 0 && premiumRate > 0 && (
                <div className="rounded-lg border bg-muted/40 p-3 space-y-1">
                  <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Our Financial Position</p>
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">Accepted SI ({ourSharePct}%)</span>
                    <span className="font-medium">{formatNaira(acceptedSumInsured)}</span>
                  </div>
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">Gross Premium</span>
                    <span className="font-medium">{formatNaira(grossPremium)}</span>
                  </div>
                  {commissionRate > 0 && (
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Ceding Commission ({commissionRate}%)</span>
                      <span className="text-destructive">−{formatNaira(commissionAmount)}</span>
                    </div>
                  )}
                  <Separator />
                  <div className="flex justify-between text-sm font-semibold">
                    <span>Net Premium Receivable</span>
                    <span className="text-primary">{formatNaira(netPremium)}</span>
                  </div>
                </div>
              )}

              <Separator />
              <p className="text-sm font-semibold text-foreground">Cover Period</p>

              <FormRow>
                <FormField control={form.control} name="coverFrom"
                  render={({ field }) => (<FormItem><FormLabel>Cover From</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
                <FormField control={form.control} name="coverTo"
                  render={({ field }) => (<FormItem><FormLabel>Cover To</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              </FormRow>

              <SheetFooter className="pt-2">
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
                <Button type="submit" disabled={create.isPending}>
                  {create.isPending ? 'Creating…' : 'Accept Inward FAC'}
                </Button>
              </SheetFooter>
            </form>
          </Form>
        )}
      </SheetContent>
    </Sheet>
  );
}
