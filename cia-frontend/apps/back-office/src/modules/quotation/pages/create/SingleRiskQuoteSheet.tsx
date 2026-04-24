import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

const schema = z.object({
  customerId:        z.string().min(1, 'Select a customer'),
  productId:         z.string().min(1, 'Select a product'),
  startDate:         z.string().min(1, 'Required'),
  endDate:           z.string().min(1, 'Required'),
  sumInsured:        z.coerce.number().positive('Must be positive'),
  rate:              z.coerce.number().min(0).max(100),
  discount:          z.coerce.number().min(0),
  riskDescription:   z.string().min(5, 'Describe the risk'),
});
type FormValues = z.infer<typeof schema>;

const mockCustomers = [
  { id: 'c1', name: 'Chioma Okafor' },
  { id: 'c2', name: 'Alaba Trading Co.' },
  { id: 'c3', name: 'Emeka Eze' },
];

const mockProducts = [
  { id: 'p1', name: 'Private Motor Comprehensive', defaultRate: 2.25 },
  { id: 'p2', name: 'Commercial Vehicle',          defaultRate: 1.80 },
  { id: 'p3', name: 'Fire & Burglary Standard',    defaultRate: 0.80 },
  { id: 'p4', name: 'Marine Cargo Open Cover',     defaultRate: 0.75 },
];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function SingleRiskQuoteSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { customerId: '', productId: '', startDate: '', endDate: '', sumInsured: 0, rate: 0, discount: 0, riskDescription: '' },
  });

  const sumInsured = form.watch('sumInsured') || 0;
  const rate       = form.watch('rate')       || 0;
  const discount   = form.watch('discount')   || 0;
  const grossPremium = (sumInsured * rate) / 100;
  const netPremium   = grossPremium - discount;

  function onProductChange(productId: string, fieldOnChange: (v: string) => void) {
    fieldOnChange(productId);
    const product = mockProducts.find(p => p.id === productId);
    if (product) form.setValue('rate', product.defaultRate);
  }

  async function onSubmit(values: FormValues) {
    console.log('Create single-risk quote', values);
    // TODO: POST /api/v1/quotes
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Single-Risk Quote</SheetTitle>
          <SheetDescription>
            Generate a quote for one insured risk. The premium is calculated automatically from the sum insured and rate.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            {/* Customer */}
            <FormField control={form.control} name="customerId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Customer</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select customer" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {mockCustomers.map(c => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Product */}
            <FormField control={form.control} name="productId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Product</FormLabel>
                  <Select onValueChange={(v) => onProductChange(v, field.onChange)} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select product" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {mockProducts.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
                      <SelectSeparator />
                      <SelectItem value="__new__" className="text-primary font-medium">+ New Product</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Policy period */}
            <FormRow>
              <FormField control={form.control} name="startDate"
                render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="endDate"
                render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <Separator />

            {/* Risk details */}
            <p className="text-sm font-semibold text-foreground">Risk & Premium</p>

            <FormField control={form.control} name="riskDescription"
              render={({ field }) => (<FormItem><FormLabel>Risk Description</FormLabel><FormControl><Input placeholder="e.g. 2022 Toyota Camry, Reg: LND-001-AA" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="sumInsured"
                render={({ field }) => (<FormItem><FormLabel>Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} step={100000} {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="rate"
                render={({ field }) => (<FormItem><FormLabel>Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="discount"
              render={({ field }) => (<FormItem><FormLabel>Discount (₦)</FormLabel><FormControl><Input type="number" min={0} {...field} /></FormControl><FormMessage /></FormItem>)} />

            {/* Premium preview */}
            {sumInsured > 0 && rate > 0 && (
              <div className="rounded-lg border bg-muted/40 p-4 space-y-1.5">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Premium Summary</p>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Gross Premium</span>
                  <span className="font-medium">₦{grossPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                {discount > 0 && (
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">Discount</span>
                    <span className="text-destructive">−₦{discount.toLocaleString()}</span>
                  </div>
                )}
                <Separator />
                <div className="flex justify-between text-sm font-semibold">
                  <span>Net Premium</span>
                  <span className="text-primary">₦{netPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
              </div>
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : 'Save as Draft'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
