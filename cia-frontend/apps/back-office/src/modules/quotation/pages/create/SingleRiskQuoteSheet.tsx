import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Checkbox,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useState } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import type { Control } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type CustomerDto,
  type ProductDto,
} from '@cia/api-client';
import { INITIAL_CLAUSES } from '../clauses-shared';
import { applyApiErrors } from '@/lib/form-errors';

interface AdjustmentTypeDto { id: string; name: string; }

// ── Schema ────────────────────────────────────────────────────────────────────
const adjustmentSchema = z.object({
  typeId: z.string().min(1, 'Select a type'),
  format: z.enum(['PERCENT', 'FLAT']),
  value:  z.coerce.number().min(0, 'Must be ≥ 0'),
});

const schema = z.object({
  customerId:        z.string().min(1, 'Select a customer'),
  productId:         z.string().min(1, 'Select a product'),
  startDate:         z.string().min(1, 'Required'),
  endDate:           z.string().min(1, 'Required'),
  sumInsured:        z.coerce.number().positive('Must be positive'),
  rate:              z.coerce.number().min(0).max(100),
  riskDescription:   z.string().min(5, 'Describe the risk'),
  loadings:          z.array(adjustmentSchema),
  discounts:         z.array(adjustmentSchema),
  selectedClauseIds: z.array(z.string()),
});
export type SingleRiskFormValues = z.infer<typeof schema>;

// Customer summary as returned by GET /api/v1/customers — display name varies by type.
function customerLabel(c: CustomerDto & { firstName?: string; lastName?: string; companyName?: string }): string {
  if (c.customerType === 'CORPORATE') return c.companyName ?? '(unnamed corporate)';
  return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || '(unnamed individual)';
}

// Product list shape: backend returns ProductDto with productRate (default rate).
type ProductWithRate = ProductDto & { productRate?: number };

// ── Adjustment row array ──────────────────────────────────────────────────────
function AdjustmentRows({
  control, name, label, typeOptions, accentColor,
}: {
  control: Control<SingleRiskFormValues>;
  name: 'loadings' | 'discounts';
  label: string;
  typeOptions: { id: string; name: string }[];
  accentColor: 'amber' | 'rose';
}) {
  const { fields, append, remove } = useFieldArray({ control, name });

  const accent = accentColor === 'amber'
    ? 'bg-amber-50 border-amber-200 text-amber-700'
    : 'bg-rose-50 border-rose-200 text-rose-700';

  return (
    <div className="space-y-2">
      {fields.map((f, i) => (
        <div key={f.id} className={`rounded-md border p-2.5 space-y-2 ${accentColor === 'amber' ? 'bg-amber-50/40' : 'bg-rose-50/40'}`}>
          <div className="grid grid-cols-[1fr_100px_90px_auto] gap-2 items-end">
            <FormField control={control} name={`${name}.${i}.typeId`}
              render={({ field }) => (
                <FormItem className="space-y-1">
                  <FormLabel className="text-xs">{label} Type</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {typeOptions.map(t => <SelectItem key={t.id} value={t.id} className="text-xs">{t.name}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={control} name={`${name}.${i}.format`}
              render={({ field }) => (
                <FormItem className="space-y-1">
                  <FormLabel className="text-xs">Format</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger className="h-8 text-xs"><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="PERCENT" className="text-xs">% Rate</SelectItem>
                      <SelectItem value="FLAT" className="text-xs">₦ Flat</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={control} name={`${name}.${i}.value`}
              render={({ field }) => (
                <FormItem className="space-y-1">
                  <FormLabel className="text-xs">Value</FormLabel>
                  <FormControl><Input type="number" min={0} step={0.01} className="h-8 text-xs" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <Button type="button" variant="ghost" size="sm"
              className="h-8 w-8 p-0 text-muted-foreground hover:text-destructive self-end"
              onClick={() => remove(i)}>✕</Button>
          </div>
        </div>
      ))}
      <Button type="button" variant="ghost" size="sm"
        className={`h-7 text-xs border rounded-md px-2 ${accent}`}
        onClick={() => append({ typeId: '', format: 'PERCENT', value: 0 })}>
        + Add {label}
      </Button>
    </div>
  );
}

// ── Main sheet ────────────────────────────────────────────────────────────────
interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

type CustomerSummary = CustomerDto & { firstName?: string; lastName?: string; companyName?: string };

export default function SingleRiskQuoteSheet({ open, onOpenChange, onSuccess }: Props) {
  const [clauseSearch, setClauseSearch] = useState('');
  const queryClient = useQueryClient();

  const customersQuery = useQuery<CustomerSummary[]>({
    queryKey: ['customers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CustomerSummary[] }>('/api/v1/customers');
      return res.data.data;
    },
    enabled: open,
  });
  const customers = customersQuery.data ?? [];

  const productsQuery = useQuery<ProductWithRate[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ProductWithRate[] }>('/api/v1/setup/products');
      return res.data.data;
    },
    enabled: open,
  });
  const products = productsQuery.data ?? [];

  const loadingTypesQuery = useQuery<AdjustmentTypeDto[]>({
    queryKey: ['setup', 'quote-loading-types'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: AdjustmentTypeDto[] }>('/api/v1/setup/quote-loading-types');
      return res.data.data;
    },
    enabled: open,
  });
  const loadingTypes = loadingTypesQuery.data ?? [];

  const discountTypesQuery = useQuery<AdjustmentTypeDto[]>({
    queryKey: ['setup', 'quote-discount-types'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: AdjustmentTypeDto[] }>('/api/v1/setup/quote-discount-types');
      return res.data.data;
    },
    enabled: open,
  });
  const discountTypes = discountTypesQuery.data ?? [];

  const form = useForm<SingleRiskFormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues: {
      customerId: '', productId: '', startDate: '', endDate: '',
      sumInsured: 0, rate: 0, riskDescription: '',
      loadings: [], discounts: [], selectedClauseIds: [],
    },
  });

  const sumInsured = useWatch({ control: form.control, name: 'sumInsured' }) || 0;
  const rate       = useWatch({ control: form.control, name: 'rate' })       || 0;
  const loadings   = useWatch({ control: form.control, name: 'loadings' })   || [];
  const discounts  = useWatch({ control: form.control, name: 'discounts' })  || [];

  const gross = (sumInsured * rate) / 100;
  const totalLoading = loadings.reduce((sum: number, l: any) => {
    return sum + (l.format === 'PERCENT' ? gross * l.value / 100 : l.value);
  }, 0);
  const loaded = gross + totalLoading;
  const totalDiscount = discounts.reduce((sum: number, d: any) => {
    return sum + (d.format === 'PERCENT' ? loaded * d.value / 100 : d.value);
  }, 0);
  const netPremium = Math.max(0, loaded - totalDiscount);

  function onProductChange(productId: string, fieldOnChange: (v: string) => void) {
    fieldOnChange(productId);
    const product = products.find(p => p.id === productId);
    if (product?.productRate != null) form.setValue('rate', product.productRate);
  }

  // Resolve a type-id → name lookup so we can denormalize the typeName into
  // each AdjustmentEntry payload (matches the backend AdjustmentEntry shape).
  function resolveTypeName(typeId: string, kind: 'loading' | 'discount'): string {
    const list = kind === 'loading' ? loadingTypes : discountTypes;
    return list.find(t => t.id === typeId)?.name ?? '';
  }

  const createQuote = useMutation({
    mutationFn: async (values: SingleRiskFormValues) => {
      const payload = {
        customerId:        values.customerId,
        productId:         values.productId,
        businessType:      'DIRECT',
        policyStartDate:   values.startDate,
        policyEndDate:     values.endDate,
        risks: [{
          description: values.riskDescription,
          sumInsured:  values.sumInsured,
          rate:        values.rate,
          loadings:    values.loadings.map(l => ({ ...l, typeName: resolveTypeName(l.typeId, 'loading') })),
          discounts:   values.discounts.map(d => ({ ...d, typeName: resolveTypeName(d.typeId, 'discount') })),
        }],
        quoteLoadings:     [],
        quoteDiscounts:    [],
        selectedClauseIds: values.selectedClauseIds,
      };
      const res = await apiClient.post<{ data: { id: string } }>('/api/v1/quotes', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['quotes'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not create quote' }),
  });

  function onSubmit(values: SingleRiskFormValues) {
    createQuote.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Single-Risk Quote</SheetTitle>
          <SheetDescription>
            Generate a quote for one insured risk with optional loadings and discounts.
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
                      {customers.map(c => <SelectItem key={c.id} value={c.id}>{customerLabel(c)}</SelectItem>)}
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
                      {products.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
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

            {/* Loadings */}
            <div className="space-y-2">
              <p className="text-xs font-medium text-amber-700">Loadings</p>
              <AdjustmentRows
                control={form.control}
                name="loadings"
                label="Loading"
                typeOptions={loadingTypes}
                accentColor="amber"
              />
            </div>

            {/* Discounts */}
            <div className="space-y-2">
              <p className="text-xs font-medium text-rose-700">Discounts</p>
              <AdjustmentRows
                control={form.control}
                name="discounts"
                label="Discount"
                typeOptions={discountTypes}
                accentColor="rose"
              />
            </div>

            {/* Premium preview */}
            {sumInsured > 0 && rate > 0 && (
              <div className="rounded-lg border bg-muted/40 p-4 space-y-1.5">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Premium Summary</p>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Gross Premium</span>
                  <span className="font-medium">₦{gross.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                {totalLoading > 0 && (
                  <div className="flex justify-between text-sm text-amber-700">
                    <span>+ Total Loading</span>
                    <span>₦{totalLoading.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                {totalDiscount > 0 && (
                  <div className="flex justify-between text-sm text-rose-700">
                    <span>− Total Discount</span>
                    <span>₦{totalDiscount.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                <Separator />
                <div className="flex justify-between text-sm font-semibold">
                  <span>Net Premium</span>
                  <span className="text-primary">₦{netPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
              </div>
            )}

            <Separator />

            {/* Clause selection */}
            <div className="space-y-3">
              <p className="text-sm font-semibold text-foreground">Applicable Clauses</p>
              <p className="text-xs text-muted-foreground">Select clauses from the clause bank that apply to this quote.</p>
              <Input
                placeholder="Search clauses…"
                value={clauseSearch}
                onChange={e => setClauseSearch(e.target.value)}
                className="h-8 text-sm"
              />
              <div className="space-y-2 max-h-44 overflow-y-auto rounded-md border p-3">
                {INITIAL_CLAUSES.filter(c =>
                  clauseSearch === '' ||
                  c.title.toLowerCase().includes(clauseSearch.toLowerCase()) ||
                  c.text.toLowerCase().includes(clauseSearch.toLowerCase())
                ).map(clause => (
                  <FormField key={clause.id} control={form.control} name="selectedClauseIds"
                    render={({ field }) => {
                      const checked = field.value.includes(clause.id);
                      return (
                        <div className="flex items-start gap-2 py-1">
                          <Checkbox
                            id={`sr-clause-${clause.id}`}
                            checked={checked}
                            onCheckedChange={(c) => {
                              field.onChange(c
                                ? [...field.value, clause.id]
                                : field.value.filter((id: string) => id !== clause.id));
                            }}
                          />
                          <label htmlFor={`sr-clause-${clause.id}`} className="cursor-pointer space-y-0.5">
                            <p className="text-sm font-medium leading-none">{clause.title}</p>
                            <p className="text-xs text-muted-foreground line-clamp-1">{clause.text}</p>
                          </label>
                        </div>
                      );
                    }}
                  />
                ))}
              </div>
            </div>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={createQuote.isPending}>
                {createQuote.isPending ? 'Saving…' : 'Save as Draft'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
