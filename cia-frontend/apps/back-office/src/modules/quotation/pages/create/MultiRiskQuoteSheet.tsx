import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Checkbox,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useState } from 'react';
import { useFieldArray, useForm, useWatch, Control } from 'react-hook-form';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type CustomerDto,
  type ProductDto,
} from '@cia/api-client';
import { INITIAL_CLAUSES } from '../clauses-shared';

interface AdjustmentTypeDto { id: string; name: string; }
type CustomerSummary = CustomerDto & { firstName?: string; lastName?: string; companyName?: string };
function customerLabel(c: CustomerSummary): string {
  if (c.customerType === 'CORPORATE') return c.companyName ?? '(unnamed corporate)';
  return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || '(unnamed individual)';
}

// ── Schema ────────────────────────────────────────────────────────────────────
const adjustmentSchema = z.object({
  typeId:  z.string().min(1, 'Select a type'),
  format:  z.enum(['PERCENT', 'FLAT']),
  value:   z.coerce.number().min(0, 'Must be ≥ 0'),
});

const riskSchema = z.object({
  description: z.string().min(3, 'Required'),
  sumInsured:  z.coerce.number().positive('Must be positive'),
  rate:        z.coerce.number().min(0),
  loadings:    z.array(adjustmentSchema),
  discounts:   z.array(adjustmentSchema),
});

const schema = z.object({
  customerId:       z.string().min(1, 'Required'),
  productId:        z.string().min(1, 'Required'),
  startDate:        z.string().min(1, 'Required'),
  endDate:          z.string().min(1, 'Required'),
  risks:            z.array(riskSchema).min(1),
  quoteLoadings:    z.array(adjustmentSchema),
  quoteDiscounts:   z.array(adjustmentSchema),
  selectedClauseIds: z.array(z.string()),
});
export type MultiRiskFormValues = z.infer<typeof schema>;

function computeItemNet(si: number, rate: number, loadings: { format: string; value: number }[], discounts: { format: string; value: number }[]) {
  const gross = (si * rate) / 100;
  const totalLoading = loadings.reduce((sum, l) => {
    return sum + (l.format === 'PERCENT' ? gross * l.value / 100 : l.value);
  }, 0);
  const loaded = gross + totalLoading;
  const totalDiscount = discounts.reduce((sum, d) => {
    return sum + (d.format === 'PERCENT' ? loaded * d.value / 100 : d.value);
  }, 0);
  return { gross, loaded, net: Math.max(0, loaded - totalDiscount) };
}

// ── Adjustment sub-array (shared for loadings + discounts) ────────────────────
function AdjustmentRows({
  control, name, label, typeOptions, accentColor,
}: {
  control: Control<MultiRiskFormValues>;
  name: `risks.${number}.loadings` | `risks.${number}.discounts` | 'quoteLoadings' | 'quoteDiscounts';
  label: string;
  typeOptions: { id: string; name: string }[];
  accentColor: 'amber' | 'rose';
}) {
  const { fields, append, remove } = useFieldArray({ control, name: name as any });

  const accent = accentColor === 'amber'
    ? 'bg-amber-50 border-amber-200 text-amber-700'
    : 'bg-rose-50 border-rose-200 text-rose-700';

  return (
    <div className="space-y-2">
      {fields.map((f, i) => (
        <div key={f.id} className={`rounded-md border p-2.5 space-y-2 ${accentColor === 'amber' ? 'bg-amber-50/40' : 'bg-rose-50/40'}`}>
          <div className="grid grid-cols-[1fr_100px_90px_auto] gap-2 items-end">
            {/* Type */}
            <FormField control={control} name={`${name}.${i}.typeId` as any}
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
            {/* Format */}
            <FormField control={control} name={`${name}.${i}.format` as any}
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
            {/* Value */}
            <FormField control={control} name={`${name}.${i}.value` as any}
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

// ── Single risk item card ─────────────────────────────────────────────────────
function RiskItemCard({
  index, control, remove: removeItem, loadingTypes, discountTypes,
}: {
  index: number;
  control: Control<MultiRiskFormValues>;
  remove: () => void;
  loadingTypes:  AdjustmentTypeDto[];
  discountTypes: AdjustmentTypeDto[];
}) {
  const si       = useWatch({ control, name: `risks.${index}.sumInsured` }) || 0;
  const rate     = useWatch({ control, name: `risks.${index}.rate` })       || 0;
  const loadings = useWatch({ control, name: `risks.${index}.loadings` })   || [];
  const discounts= useWatch({ control, name: `risks.${index}.discounts` })  || [];
  const { gross, loaded, net } = computeItemNet(si, rate, loadings, discounts);

  return (
    <div className="rounded-lg border p-4 space-y-4 bg-card">
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Item {index + 1}</p>
        <Button type="button" variant="ghost" size="sm" className="h-7 text-xs text-destructive" onClick={removeItem}>
          Remove
        </Button>
      </div>

      {/* Description */}
      <FormField control={control} name={`risks.${index}.description`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="text-xs">Description</FormLabel>
            <FormControl><Input placeholder="e.g. Stock in warehouse A" {...field} /></FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      {/* SI + Rate */}
      <FormRow>
        <FormField control={control} name={`risks.${index}.sumInsured`}
          render={({ field }) => (
            <FormItem>
              <FormLabel className="text-xs">Sum Insured (₦)</FormLabel>
              <FormControl><Input type="number" min={0} {...field} /></FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField control={control} name={`risks.${index}.rate`}
          render={({ field }) => (
            <FormItem>
              <FormLabel className="text-xs">Rate (%)</FormLabel>
              <FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </FormRow>

      {/* Loadings */}
      <div>
        <p className="text-xs font-medium text-amber-700 mb-2">Loadings</p>
        <AdjustmentRows
          control={control}
          name={`risks.${index}.loadings`}
          label="Loading"
          typeOptions={loadingTypes}
          accentColor="amber"
        />
      </div>

      {/* Discounts */}
      <div>
        <p className="text-xs font-medium text-rose-700 mb-2">Discounts</p>
        <AdjustmentRows
          control={control}
          name={`risks.${index}.discounts`}
          label="Discount"
          typeOptions={discountTypes}
          accentColor="rose"
        />
      </div>

      {/* Per-item preview */}
      {si > 0 && rate > 0 && (
        <div className="rounded-md bg-muted/40 border px-3 py-2 space-y-1 text-xs">
          <div className="flex justify-between text-muted-foreground">
            <span>Gross Premium</span>
            <span>₦{gross.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
          </div>
          {loadings.length > 0 && (
            <div className="flex justify-between text-amber-700">
              <span>+ Loadings</span>
              <span>₦{(loaded - gross).toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
            </div>
          )}
          {discounts.length > 0 && (
            <div className="flex justify-between text-rose-700">
              <span>− Discounts</span>
              <span>₦{(loaded - net).toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
            </div>
          )}
          <div className="flex justify-between font-semibold border-t pt-1">
            <span>Item Net</span>
            <span className="text-primary">₦{net.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Main sheet ────────────────────────────────────────────────────────────────
interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function MultiRiskQuoteSheet({ open, onOpenChange, onSuccess }: Props) {
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

  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ProductDto[] }>('/api/v1/setup/products');
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

  const form = useForm<MultiRiskFormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues: {
      customerId: '', productId: '', startDate: '', endDate: '',
      risks: [{ description: '', sumInsured: 0, rate: 0, loadings: [], discounts: [] }],
      quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: [],
    },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'risks' });

  // ── Totals ────────────────────────────────────────────────────────────────
  const risks         = useWatch({ control: form.control, name: 'risks' }) || [];
  const quoteLoadings = useWatch({ control: form.control, name: 'quoteLoadings' }) || [];
  const quoteDiscounts= useWatch({ control: form.control, name: 'quoteDiscounts' }) || [];

  const totals = risks.reduce(
    (acc, r) => {
      const { gross, net } = computeItemNet(r.sumInsured || 0, r.rate || 0, r.loadings || [], r.discounts || []);
      return { totalGross: acc.totalGross + gross, totalItemNets: acc.totalItemNets + net };
    },
    { totalGross: 0, totalItemNets: 0 }
  );

  const totalQuoteLoading = quoteLoadings.reduce((sum, l) => {
    return sum + (l.format === 'PERCENT' ? totals.totalGross * l.value / 100 : l.value);
  }, 0);
  const quoteLoadedBase = totals.totalItemNets + totalQuoteLoading;
  const totalQuoteDiscount = quoteDiscounts.reduce((sum, d) => {
    return sum + (d.format === 'PERCENT' ? quoteLoadedBase * d.value / 100 : d.value);
  }, 0);
  const finalNet = Math.max(0, quoteLoadedBase - totalQuoteDiscount);

  function resolveTypeName(typeId: string, kind: 'loading' | 'discount'): string {
    const list = kind === 'loading' ? loadingTypes : discountTypes;
    return list.find(t => t.id === typeId)?.name ?? '';
  }

  const createQuote = useMutation({
    mutationFn: async (values: MultiRiskFormValues) => {
      const payload = {
        customerId:      values.customerId,
        productId:       values.productId,
        businessType:    'DIRECT',
        policyStartDate: values.startDate,
        policyEndDate:   values.endDate,
        risks: values.risks.map(r => ({
          description: r.description,
          sumInsured:  r.sumInsured,
          rate:        r.rate,
          loadings:    r.loadings.map(l => ({ ...l, typeName: resolveTypeName(l.typeId, 'loading') })),
          discounts:   r.discounts.map(d => ({ ...d, typeName: resolveTypeName(d.typeId, 'discount') })),
        })),
        quoteLoadings:    values.quoteLoadings.map(l => ({ ...l, typeName: resolveTypeName(l.typeId, 'loading') })),
        quoteDiscounts:   values.quoteDiscounts.map(d => ({ ...d, typeName: resolveTypeName(d.typeId, 'discount') })),
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
  });

  function onSubmit(values: MultiRiskFormValues) {
    createQuote.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-2xl overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Multi-Risk Quote</SheetTitle>
          <SheetDescription>
            Add multiple insured items. Each item can have loadings and discounts. Clause selection applies to the whole quote.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* Header fields */}
            <FormRow>
              <FormField control={form.control} name="customerId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Customer</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{customers.map(c => <SelectItem key={c.id} value={c.id}>{customerLabel(c)}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="productId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Product</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                      <SelectContent>{products.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormRow>
              <FormField control={form.control} name="startDate"
                render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="endDate"
                render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <Separator />

            {/* Risk items */}
            <p className="text-sm font-semibold text-foreground">Risk Items</p>
            <div className="space-y-4">
              {fields.map((f, i) => (
                <RiskItemCard
                  key={f.id}
                  index={i}
                  control={form.control}
                  remove={() => remove(i)}
                  loadingTypes={loadingTypes}
                  discountTypes={discountTypes}
                />
              ))}
            </div>
            <Button type="button" variant="outline" size="sm"
              onClick={() => append({ description: '', sumInsured: 0, rate: 0, loadings: [], discounts: [] })}>
              + Add Risk Item
            </Button>

            <Separator />

            {/* Quote-level adjustments */}
            <p className="text-sm font-semibold text-foreground">Quote-Level Adjustments</p>
            <p className="text-xs text-muted-foreground -mt-3">
              Applied on top of the sum of individual item net premiums. Percentage loadings use total gross as the base.
            </p>

            <div>
              <p className="text-xs font-medium text-amber-700 mb-2">Quote Loadings</p>
              <AdjustmentRows
                control={form.control}
                name="quoteLoadings"
                label="Loading"
                typeOptions={loadingTypes}
                accentColor="amber"
              />
            </div>

            <div>
              <p className="text-xs font-medium text-rose-700 mb-2">Quote Discounts</p>
              <AdjustmentRows
                control={form.control}
                name="quoteDiscounts"
                label="Discount"
                typeOptions={discountTypes}
                accentColor="rose"
              />
            </div>

            {/* Grand total */}
            {totals.totalGross > 0 && (
              <div className="rounded-lg border bg-muted/40 p-4 space-y-1.5">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Quote Summary</p>
                <div className="flex justify-between text-sm text-muted-foreground">
                  <span>Total Gross Premium</span>
                  <span>₦{totals.totalGross.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                <div className="flex justify-between text-sm text-muted-foreground">
                  <span>Sum of Item Nets</span>
                  <span>₦{totals.totalItemNets.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                </div>
                {totalQuoteLoading > 0 && (
                  <div className="flex justify-between text-sm text-amber-700">
                    <span>+ Quote Loading</span>
                    <span>₦{totalQuoteLoading.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                {totalQuoteDiscount > 0 && (
                  <div className="flex justify-between text-sm text-rose-700">
                    <span>− Quote Discount</span>
                    <span>₦{totalQuoteDiscount.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
                  </div>
                )}
                <Separator />
                <div className="flex justify-between text-sm font-semibold">
                  <span>Final Net Premium</span>
                  <span className="text-primary text-base">₦{finalNet.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
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
              <div className="space-y-2 max-h-48 overflow-y-auto rounded-md border p-3">
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
                            id={`clause-${clause.id}`}
                            checked={checked}
                            onCheckedChange={(c) => {
                              field.onChange(c
                                ? [...field.value, clause.id]
                                : field.value.filter((id: string) => id !== clause.id));
                            }}
                          />
                          <label htmlFor={`clause-${clause.id}`} className="cursor-pointer space-y-0.5">
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
