import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { ProductDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

const schema = z.object({
  name:             z.string().min(2, 'Required'),
  code:             z.string().min(2, 'Required'),
  classOfBusinessId:z.string().min(1, 'Required'),
  type:             z.enum(['SINGLE_RISK', 'MULTI_RISK']),
  commissionRate:   z.coerce.number().min(0).max(100),
});
type FormValues = z.infer<typeof schema>;

const mockClasses = [
  { id: '1', name: 'Motor (Private)' },
  { id: '2', name: 'Motor (Commercial)' },
  { id: '3', name: 'Fire & Burglary' },
  { id: '4', name: 'Marine Cargo' },
];

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  product: ProductDto | null; onSuccess: () => void;
}

export default function ProductSheet({ open, onOpenChange, product, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { name: '', code: '', classOfBusinessId: '', type: 'SINGLE_RISK', commissionRate: 10 },
  });

  useEffect(() => {
    form.reset(product ? {
      name:             product.name,
      code:             product.code,
      classOfBusinessId:product.classOfBusinessId,
      type:             product.type,
      commissionRate:   product.commissionRate,
    } : { name: '', code: '', classOfBusinessId: '', type: 'SINGLE_RISK', commissionRate: 10 });
  }, [product, form]);

  async function onSubmit(values: FormValues) {
    console.log(product ? 'Update product' : 'Create product', values);
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{product ? 'Edit Product' : 'New Product'}</SheetTitle>
          <SheetDescription>Products define what risks can be insured and at what commission rate.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormField control={form.control} name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Product Name</FormLabel>
                  <FormControl><Input placeholder="e.g. Private Motor Comprehensive" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormRow>
              <FormField control={form.control} name="code"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Product Code</FormLabel>
                    <FormControl><Input placeholder="e.g. PMC-001" className="uppercase" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="commissionRate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Commission (%)</FormLabel>
                    <FormControl><Input type="number" min={0} max={100} step={0.5} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="classOfBusinessId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Class of Business</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select class" /></SelectTrigger></FormControl>
                      <SelectContent>{mockClasses.map((c) => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="type"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Risk Type</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                      <SelectContent>
                        <SelectItem value="SINGLE_RISK">Single Risk</SelectItem>
                        <SelectItem value="MULTI_RISK">Multi Risk</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : product ? 'Save Changes' : 'Create Product'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
