import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ProductDto, type ClassOfBusinessDto } from '@cia/api-client';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

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

const CREATE_NEW_SENTINEL = '__create_new__';

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  product: ProductDto | null;
  onSuccess: () => void;
}

export default function ProductSheet({ open, onOpenChange, product, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const [createClassOpen, setCreateClassOpen] = useState(false);

  // Live class-of-business list from the API. New rows created via the
  // inline dialog cause this query to invalidate and refetch.
  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>(
        '/api/v1/setup/classes-of-business',
      );
      return res.data.data;
    },
    enabled: open,
  });
  const classes = classesQuery.data ?? [];

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

  const saveProduct = useMutation({
    mutationFn: async (values: ProductFormValues) => {
      if (product) {
        const res = await apiClient.put<{ data: ProductDto }>(
          `/api/v1/setup/products/${product.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ProductDto }>(
        '/api/v1/setup/products', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'products'] });
      onSuccess();
    },
  });

  function onSubmit(values: ProductFormValues) {
    saveProduct.mutate(values);
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

  const createClass = useMutation({
    mutationFn: async (values: ClassFormValues) => {
      const res = await apiClient.post<{ data: ClassOfBusinessDto }>(
        '/api/v1/setup/classes-of-business', values,
      );
      return res.data.data;
    },
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'classes-of-business'] });
      form.setValue('classOfBusinessId', created.id); // auto-select the new class
      setCreateClassOpen(false);
    },
  });

  function onCreateClass(values: ClassFormValues) {
    createClass.mutate(values);
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
                <Button type="submit" disabled={saveProduct.isPending}>
                  {saveProduct.isPending
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
                <Button type="submit" disabled={createClass.isPending}>
                  {createClass.isPending ? 'Creating…' : 'Create Class'}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </>
  );
}
