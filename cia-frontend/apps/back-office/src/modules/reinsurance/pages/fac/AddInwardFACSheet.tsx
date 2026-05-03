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
import { apiClient, type ClassOfBusinessDto } from '@cia/api-client';
import { z } from 'zod';

interface InsurerDto { id: string; name: string; }

const schema = z.object({
  cedingCompanyId:   z.string().min(1, 'Select the ceding company'),
  cedingReference:   z.string().min(2, 'Enter their reference number'),
  classOfBusiness:   z.string().min(1, 'Select class of business'),
  riskDescription:   z.string().min(5, 'Describe the risk'),
  location:          z.string().min(3, 'Required'),
  totalSumInsured:   z.coerce.number().positive(),
  ourShare:          z.coerce.number().min(1, 'Min 1%').max(100, 'Max 100%'),
  premiumRate:       z.coerce.number().min(0).max(100),
  cedingCommission:  z.coerce.number().min(0).max(50),
  startDate:         z.string().min(1, 'Required'),
  endDate:           z.string().min(1, 'Required'),
  contactName:       z.string().min(2, 'Required'),
  contactEmail:      z.string().email('Invalid email').optional().or(z.literal('')),
});
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function AddInwardFACSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const insurersQuery = useQuery<InsurerDto[]>({
    queryKey: ['setup', 'insurance-companies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: InsurerDto[] }>('/api/v1/setup/insurance-companies');
      return res.data.data;
    },
    enabled: open,
  });
  const cedingCompanies = insurersQuery.data ?? [];

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
      cedingCompanyId: '', cedingReference: '', classOfBusiness: '',
      riskDescription: '', location: '', totalSumInsured: 0,
      ourShare: 0, premiumRate: 0, cedingCommission: 0,
      startDate: '', endDate: '', contactName: '', contactEmail: '',
    },
  });

  const totalSI       = form.watch('totalSumInsured') || 0;
  const ourShare      = form.watch('ourShare')        || 0;
  const premiumRate   = form.watch('premiumRate')     || 0;
  const cedingComm    = form.watch('cedingCommission')|| 0;

  const ourSI          = totalSI * ourShare / 100;
  const grossPremium   = ourSI * premiumRate / 100;
  const commAmount     = grossPremium * cedingComm / 100;
  const netPremiumDue  = grossPremium - commAmount;  // what we receive

  const create = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        '/api/v1/reinsurance/fac/inward', values,
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

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Add Inward FAC Policy</SheetTitle>
          <SheetDescription>
            Record a facultative risk accepted from another insurance company. We become the reinsurer.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            {/* Ceding company */}
            <FormRow>
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
              <FormField control={form.control} name="cedingReference"
                render={({ field }) => (<FormItem><FormLabel>Their Reference No.</FormLabel><FormControl><Input placeholder="e.g. FAC-LWY-2026-001" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="classOfBusiness"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Class of Business</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select class" /></SelectTrigger></FormControl>
                    <SelectContent>{classes.map(c => <SelectItem key={c.id} value={c.name}>{c.name}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="riskDescription"
              render={({ field }) => (<FormItem><FormLabel>Risk Description</FormLabel><FormControl><Input placeholder="Describe the insured risk" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormField control={form.control} name="location"
              render={({ field }) => (<FormItem><FormLabel>Risk Location</FormLabel><FormControl><Input placeholder="City, State" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <Separator />
            <p className="text-sm font-semibold text-foreground">Our Participation</p>

            <FormField control={form.control} name="totalSumInsured"
              render={({ field }) => (<FormItem><FormLabel>Total Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={1000000} {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="ourShare"
                render={({ field }) => (<FormItem><FormLabel>Our Share (%)</FormLabel><FormControl><Input type="number" min={1} max={100} step={1} {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="premiumRate"
                render={({ field }) => (<FormItem><FormLabel>Premium Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="cedingCommission"
              render={({ field }) => (<FormItem><FormLabel>Ceding Commission We Pay (%)</FormLabel><FormControl><Input type="number" min={0} max={50} step={0.5} {...field} /></FormControl><FormMessage /></FormItem>)} />

            {/* Premium preview */}
            {ourSI > 0 && premiumRate > 0 && (
              <div className="rounded-lg border bg-muted/40 p-3 space-y-1">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Our Financial Position</p>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Our SI ({ourShare}%)</span>
                  <span className="font-medium">₦{ourSI.toLocaleString()}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Gross Premium</span>
                  <span className="font-medium">₦{grossPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                {cedingComm > 0 && (
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">Ceding Commission ({cedingComm}%)</span>
                    <span className="text-destructive">−₦{commAmount.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                <Separator />
                <div className="flex justify-between text-sm font-semibold">
                  <span>Net Premium Receivable</span>
                  <span className="text-primary">₦{netPremiumDue.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
              </div>
            )}

            <Separator />
            <p className="text-sm font-semibold text-foreground">Cover Period</p>

            <FormRow>
              <FormField control={form.control} name="startDate"
                render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="endDate"
                render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <Separator />
            <p className="text-sm font-semibold text-foreground">Ceding Company Contact</p>

            <FormRow>
              <FormField control={form.control} name="contactName"
                render={({ field }) => (<FormItem><FormLabel>Contact Name</FormLabel><FormControl><Input placeholder="Name at ceding company" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="contactEmail"
                render={({ field }) => (<FormItem><FormLabel>Email (optional)</FormLabel><FormControl><Input type="email" placeholder="contact@company.ng" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={create.isPending}>
                {create.isPending ? 'Saving…' : 'Add Inward FAC'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
