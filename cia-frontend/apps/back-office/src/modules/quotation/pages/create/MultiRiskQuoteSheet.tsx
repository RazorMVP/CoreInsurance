import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';

const riskSchema = z.object({
  description: z.string().min(3, 'Required'),
  sumInsured:  z.coerce.number().positive(),
  rate:        z.coerce.number().min(0),
});

const schema = z.object({
  customerId: z.string().min(1, 'Required'),
  productId:  z.string().min(1, 'Required'),
  startDate:  z.string().min(1, 'Required'),
  endDate:    z.string().min(1, 'Required'),
  risks:      z.array(riskSchema).min(1),
});
type FormValues = z.infer<typeof schema>;

const mockCustomers = [{ id: 'c1', name: 'Chioma Okafor' }, { id: 'c2', name: 'Alaba Trading Co.' }];
const mockProducts  = [{ id: 'p3', name: 'Fire & Burglary Standard' }, { id: 'p4', name: 'Marine Cargo Open Cover' }];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function MultiRiskQuoteSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { customerId: '', productId: '', startDate: '', endDate: '', risks: [{ description: '', sumInsured: 0, rate: 0 }] },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'risks' });
  const risks = form.watch('risks');
  const totalSI = risks.reduce((s, r) => s + ((r.sumInsured || 0)), 0);
  const totalPremium = risks.reduce((s, r) => s + ((r.sumInsured || 0) * (r.rate || 0) / 100), 0);

  async function onSubmit(values: FormValues) {
    console.log('Create multi-risk quote', values);
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-xl overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Multi-Risk Quote</SheetTitle>
          <SheetDescription>Add multiple insured items. Each item has its own sum insured and rate.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="customerId"
                render={({ field }) => (
                  <FormItem><FormLabel>Customer</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{mockCustomers.map(c => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}</SelectContent>
                    </Select><FormMessage /></FormItem>
                )} />
              <FormField control={form.control} name="productId"
                render={({ field }) => (
                  <FormItem><FormLabel>Product</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{mockProducts.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
                    </Select><FormMessage /></FormItem>
                )} />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="startDate" render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="endDate"   render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>
            <Separator />
            <p className="text-sm font-semibold text-foreground">Risk Items</p>
            {fields.map((f, i) => (
              <div key={f.id} className="rounded-lg border p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <p className="text-xs font-medium text-muted-foreground">Item {i + 1}</p>
                  {fields.length > 1 && <Button type="button" variant="ghost" size="sm" className="h-7 text-xs text-destructive" onClick={() => remove(i)}>Remove</Button>}
                </div>
                <FormField control={form.control} name={`risks.${i}.description`} render={({ field }) => (<FormItem><FormLabel>Description</FormLabel><FormControl><Input placeholder="e.g. Stock in warehouse A" {...field} /></FormControl><FormMessage /></FormItem>)} />
                <FormRow>
                  <FormField control={form.control} name={`risks.${i}.sumInsured`} render={({ field }) => (<FormItem><FormLabel>Sum Insured (₦)</FormLabel><FormControl><Input type="number" min={0} {...field} /></FormControl><FormMessage /></FormItem>)} />
                  <FormField control={form.control} name={`risks.${i}.rate`}       render={({ field }) => (<FormItem><FormLabel>Rate (%)</FormLabel><FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
                </FormRow>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => append({ description: '', sumInsured: 0, rate: 0 })}>+ Add Item</Button>
            {totalSI > 0 && (
              <div className="rounded-lg border bg-muted/40 p-4 space-y-1.5">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Quote Summary</p>
                <div className="flex justify-between text-sm"><span className="text-muted-foreground">Total Sum Insured</span><span className="font-medium">₦{totalSI.toLocaleString()}</span></div>
                <Separator />
                <div className="flex justify-between text-sm font-semibold"><span>Total Premium</span><span className="text-primary">₦{totalPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span></div>
              </div>
            )}
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? 'Saving…' : 'Save as Draft'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
