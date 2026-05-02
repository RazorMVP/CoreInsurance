import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { ProductDto } from '@cia/api-client';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

// ── Product form schema ────────────────────────────────────────────────────
const productSchema = z.object({
  name:              z.string().min(2, 'Required'),
  code:              z.string().min(2, 'Required'),
  classOfBusinessId: z.string().min(1, 'Select a class of business'),
  type:              z.enum(['SINGLE_RISK', 'MULTI_RISK']),
  commissionRate:    z.coerce.number().min(0).max(100),
});
type ProductFormValues = z.infer<typeof productSchema>;

// ── Inline class-creation schema ───────────────────────────────────────────
const classSchema = z.object({
  name: z.string().min(2, 'Required'),
  code: z.string().min(2, 'Required').max(12, 'Max 12 characters'),
});
type ClassFormValues = z.infer<typeof classSchema>;

// ── Seed classes — replace with useList('/api/v1/setup/classes') ──────────
const INITIAL_CLASSES = [
  { id: '1',  name: 'Motor (Private)' },
  { id: '2',  name: 'Motor (Commercial)' },
  { id: '3',  name: 'Fire & Burglary' },
  { id: '4',  name: 'Marine Cargo' },
  { id: '5',  name: 'Marine Hull' },
  { id: '6',  name: 'Goods in Transit' },
  { id: '7',  name: 'Engineering / CAR' },
  { id: '8',  name: 'Professional Indemnity' },
  { id: '9',  name: 'Public Liability' },
  { id: '10', name: "Employer's Liability" },
  { id: '11', name: 'Personal Accident' },
  { id: '12', name: 'Travel Insurance' },
  { id: '13', name: 'Life (Group)' },
  { id: '14', name: 'Bonds' },
];

const CREATE_NEW_SENTINEL = '__create_new__';

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  product: ProductDto | null;
  onSuccess: () => void;
}

export default function ProductSheet({ open, onOpenChange, product, onSuccess }: Props) {
  // Local classes state — new classes created inline are appended here
  const [classes, setClasses] = useState(INITIAL_CLASSES);
  const [createClassOpen, setCreateClassOpen] = useState(false);

  // ── Product form ──────────────────────────────────────────────────────────
  const form = useForm<ProductFormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(productSchema) as any,
    defaultValues: { name: '', code: '', classOfBusinessId: '', type: 'SINGLE_RISK', commissionRate: 10 },
  });

  useEffect(() => {
    form.reset(
      product
        ? {
            name:              product.name,
            code:              product.code,
            classOfBusinessId: product.classOfBusinessId,
            type:              product.type,
            commissionRate:    product.commissionRate,
          }
        : { name: '', code: '', classOfBusinessId: '', type: 'SINGLE_RISK', commissionRate: 10 },
    );
  }, [product, form]);

  async function onSubmit(values: ProductFormValues) {
    console.log(product ? 'Update product' : 'Create product', values);
    // TODO: useCreate / useUpdate hooks
    onSuccess();
  }

  // ── Inline class-creation form ────────────────────────────────────────────
  const classForm = useForm<ClassFormValues>({
    resolver:      zodResolver(classSchema),
    defaultValues: { name: '', code: '' },
  });

  function handleClassSelectChange(
    value: string,
    fieldOnChange: (v: string) => void,
  ) {
    if (value === CREATE_NEW_SENTINEL) {
      classForm.reset({ name: '', code: '' });
      setCreateClassOpen(true);
      return; // do NOT update the product field with the sentinel
    }
    fieldOnChange(value);
  }

  async function onCreateClass(values: ClassFormValues) {
    const newId = `local-${Date.now()}`;
    const newClass = { id: newId, name: values.name };
    setClasses((prev) => [...prev, newClass]);
    form.setValue('classOfBusinessId', newId); // auto-select the new class
    setCreateClassOpen(false);
    // TODO: POST /api/v1/setup/classes then use returned ID
  }

  const selectedClassName =
    classes.find((c) => c.id === form.watch('classOfBusinessId'))?.name ?? '';

  return (
    <>
      {/* ── Product Sheet ──────────────────────────────────────────────── */}
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent side="right" className="sm:max-w-md overflow-y-auto">
          <SheetHeader>
            <SheetTitle>{product ? 'Edit Product' : 'New Product'}</SheetTitle>
            <SheetDescription>
              Products define what risks can be insured and at what commission rate.
            </SheetDescription>
          </SheetHeader>

          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Product Name</FormLabel>
                    <FormControl>
                      <Input placeholder="e.g. Private Motor Comprehensive" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormRow>
                <FormField
                  control={form.control}
                  name="code"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Product Code</FormLabel>
                      <FormControl>
                        <Input placeholder="e.g. PMC-001" className="uppercase" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="commissionRate"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Commission (%)</FormLabel>
                      <FormControl>
                        <Input type="number" min={0} max={100} step={0.5} {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </FormRow>

              <FormRow>
                {/* Class of Business with inline create ─────────────────── */}
                <FormField
                  control={form.control}
                  name="classOfBusinessId"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Class of Business</FormLabel>
                      <Select
                        onValueChange={(v) => handleClassSelectChange(v, field.onChange)}
                        value={field.value}
                      >
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue placeholder="Select class">
                              {selectedClassName || undefined}
                            </SelectValue>
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent className="max-h-64">
                          {classes.map((c) => (
                            <SelectItem key={c.id} value={c.id}>
                              {c.name}
                            </SelectItem>
                          ))}
                          <SelectSeparator />
                          <SelectItem
                            value={CREATE_NEW_SENTINEL}
                            className="text-primary font-medium"
                          >
                            + New Class of Business
                          </SelectItem>
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="type"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Risk Type</FormLabel>
                      <Select onValueChange={field.onChange} value={field.value}>
                        <FormControl>
                          <SelectTrigger><SelectValue /></SelectTrigger>
                        </FormControl>
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
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={form.formState.isSubmitting}>
                  {form.formState.isSubmitting
                    ? 'Saving…'
                    : product
                    ? 'Save Changes'
                    : 'Create Product'}
                </Button>
              </SheetFooter>
            </form>
          </Form>
        </SheetContent>
      </Sheet>

      {/* ── Inline Class Creation Dialog ───────────────────────────────── */}
      <Dialog open={createClassOpen} onOpenChange={setCreateClassOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>New Class of Business</DialogTitle>
            <DialogDescription>
              Create a new insurance class. It will be immediately available in the dropdown.
            </DialogDescription>
          </DialogHeader>

          <Form {...classForm}>
            <form
              onSubmit={classForm.handleSubmit(onCreateClass)}
              className="space-y-4"
            >
              <FormField
                control={classForm.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Class Name</FormLabel>
                    <FormControl>
                      <Input placeholder="e.g. Bonds" autoFocus {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={classForm.control}
                name="code"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Code</FormLabel>
                    <FormControl>
                      <Input
                        placeholder="e.g. BND"
                        className="uppercase"
                        maxLength={12}
                        {...field}
                        onChange={(e) =>
                          field.onChange(e.target.value.toUpperCase())
                        }
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setCreateClassOpen(false)}
                >
                  Cancel
                </Button>
                <Button type="submit" disabled={classForm.formState.isSubmitting}>
                  {classForm.formState.isSubmitting ? 'Creating…' : 'Create Class'}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </>
  );
}
