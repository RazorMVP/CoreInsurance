import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField,
  FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type CustomerDto, type ProductDto, type QuoteDto } from '@cia/api-client';
import { z } from 'zod';

type CustomerSummary = CustomerDto & { firstName?: string; lastName?: string; companyName?: string };
function customerLabel(c: CustomerSummary): string {
  if (c.customerType === 'CORPORATE') return c.companyName ?? '(unnamed corporate)';
  return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || '(unnamed individual)';
}
type ProductWithRate = ProductDto & { productRate?: number };

// ── Convert from quote ─────────────────────────────────────────────────────
const fromQuoteSchema = z.object({
  quoteId:      z.string().min(1, 'Select an approved quote'),
  businessType: z.enum(['DIRECT', 'DIRECT_WITH_COINSURANCE', 'INWARD_COINSURANCE']),
  paymentTerms: z.string().min(1, 'Required'),
  notes:        z.string().optional(),
});
type FromQuoteValues = z.infer<typeof fromQuoteSchema>;

// ── Create without quote ──────────────────────────────────────────────────
const directSchema = z.object({
  customerId:   z.string().min(1, 'Required'),
  productId:    z.string().min(1, 'Required'),
  businessType: z.enum(['DIRECT', 'DIRECT_WITH_COINSURANCE', 'INWARD_COINSURANCE']),
  startDate:    z.string().min(1, 'Required'),
  endDate:      z.string().min(1, 'Required'),
  sumInsured:   z.coerce.number().positive(),
  rate:         z.coerce.number().min(0),
  discount:     z.coerce.number().min(0),
  paymentTerms: z.string().min(1, 'Required'),
});
type DirectValues = z.infer<typeof directSchema>;

const BUSINESS_TYPES = [
  { value: 'DIRECT',                   label: 'Direct' },
  { value: 'DIRECT_WITH_COINSURANCE',  label: 'Direct with Coinsurance' },
  { value: 'INWARD_COINSURANCE',       label: 'Inward Coinsurance' },
];
const PAYMENT_TERMS = ['Immediate', '30 days', '60 days', 'Quarterly', 'Annual'];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

function FromQuoteForm({ onSuccess, onCancel }: { onSuccess: () => void; onCancel: () => void }) {
  const queryClient = useQueryClient();
  const quotesQuery = useQuery<QuoteDto[]>({
    queryKey: ['quotes', { status: 'APPROVED' }],
    queryFn: async () => {
      const res = await apiClient.get<{ data: QuoteDto[] }>('/api/v1/quotes', {
        params: { status: 'APPROVED' },
      });
      return res.data.data;
    },
  });
  const approvedQuotes = (quotesQuery.data ?? []).map(q => ({
    id:    q.id,
    label: `${q.quoteNumber} — ${q.customerName} · ${q.productName} · ₦${(q.netPremium ?? 0).toLocaleString()}`,
  }));

  const form = useForm<FromQuoteValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(fromQuoteSchema) as any,
    defaultValues: { quoteId: '', businessType: 'DIRECT', paymentTerms: '', notes: '' },
  });

  const bind = useMutation({
    mutationFn: async (values: FromQuoteValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/policies/bind-from-quote/${values.quoteId}`,
        { businessType: values.businessType, paymentTerms: values.paymentTerms, notes: values.notes },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: FromQuoteValues) {
    bind.mutate(values);
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <FormField control={form.control} name="quoteId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Approved Quote</FormLabel>
              <Select onValueChange={field.onChange} value={field.value}>
                <FormControl><SelectTrigger><SelectValue placeholder="Select approved quote" /></SelectTrigger></FormControl>
                <SelectContent>{approvedQuotes.map(q => <SelectItem key={q.id} value={q.id}>{q.label}</SelectItem>)}</SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormRow>
          <FormField control={form.control} name="businessType"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Business Type</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                  <SelectContent>{BUSINESS_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField control={form.control} name="paymentTerms"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Payment Terms</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select terms" /></SelectTrigger></FormControl>
                  <SelectContent>{PAYMENT_TERMS.map(t => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        </FormRow>
        <SheetFooter className="pt-2">
          <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
          <Button type="submit" disabled={bind.isPending}>
            {bind.isPending ? 'Creating…' : 'Issue Policy'}
          </Button>
        </SheetFooter>
      </form>
    </Form>
  );
}

function DirectForm({ onSuccess, onCancel }: { onSuccess: () => void; onCancel: () => void }) {
  const queryClient = useQueryClient();
  const customersQuery = useQuery<CustomerSummary[]>({
    queryKey: ['customers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CustomerSummary[] }>('/api/v1/customers');
      return res.data.data;
    },
  });
  const customers = customersQuery.data ?? [];

  const productsQuery = useQuery<ProductWithRate[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ProductWithRate[] }>('/api/v1/setup/products');
      return res.data.data;
    },
  });
  const products = productsQuery.data ?? [];

  const form = useForm<DirectValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(directSchema) as any,
    defaultValues: { customerId: '', productId: '', businessType: 'DIRECT', startDate: '', endDate: '', sumInsured: 0, rate: 0, discount: 0, paymentTerms: '' },
  });

  const sumInsured = form.watch('sumInsured') || 0;
  const rate       = form.watch('rate')       || 0;
  const discount   = form.watch('discount')   || 0;
  const netPremium = (sumInsured * rate / 100) - discount;

  function onProductChange(id: string, fn: (v: string) => void) {
    fn(id);
    const p = products.find(p => p.id === id);
    if (p?.productRate != null) form.setValue('rate', p.productRate);
  }

  const create = useMutation({
    mutationFn: async (values: DirectValues) => {
      const res = await apiClient.post<{ data: { id: string } }>('/api/v1/policies', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: DirectValues) {
    create.mutate(values);
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
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
                <Select onValueChange={(v) => onProductChange(v, field.onChange)} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                  <SelectContent>{products.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        </FormRow>
        <FormField control={form.control} name="businessType"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Business Type</FormLabel>
              <Select onValueChange={field.onChange} value={field.value}>
                <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                <SelectContent>{BUSINESS_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}</SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormRow>
          <FormField control={form.control} name="startDate" render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
          <FormField control={form.control} name="endDate"   render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
        </FormRow>
        <FormRow>
          <FormField control={form.control} name="sumInsured"   render={({ field }) => (<FormItem><FormLabel>Sum Insured (₦)</FormLabel><FormControl><Input type="number" {...field} /></FormControl><FormMessage /></FormItem>)} />
          <FormField control={form.control} name="rate"         render={({ field }) => (<FormItem><FormLabel>Rate (%)</FormLabel><FormControl><Input type="number" step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
        </FormRow>
        <FormRow>
          <FormField control={form.control} name="discount"     render={({ field }) => (<FormItem><FormLabel>Discount (₦)</FormLabel><FormControl><Input type="number" {...field} /></FormControl><FormMessage /></FormItem>)} />
          <FormField control={form.control} name="paymentTerms"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Payment Terms</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select" /></SelectTrigger></FormControl>
                  <SelectContent>{PAYMENT_TERMS.map(t => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        </FormRow>
        {sumInsured > 0 && rate > 0 && (
          <div className="rounded-lg border bg-muted/40 p-3 space-y-1">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Premium</p>
            <div className="flex justify-between text-sm font-semibold">
              <span>Net Premium</span>
              <span className="text-primary">₦{netPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}</span>
            </div>
          </div>
        )}
        <SheetFooter className="pt-2">
          <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Issue Policy'}
          </Button>
        </SheetFooter>
      </form>
    </Form>
  );
}

export default function CreatePolicySheet({ open, onOpenChange, onSuccess }: Props) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New Policy</SheetTitle>
          <SheetDescription>
            Issue a policy by converting an approved quote or entering details directly.
          </SheetDescription>
        </SheetHeader>
        <div className="mt-6">
          <Tabs defaultValue="quote">
            <TabsList className="w-full">
              <TabsTrigger value="quote" className="flex-1">From Approved Quote</TabsTrigger>
              <TabsTrigger value="direct" className="flex-1">Direct Entry</TabsTrigger>
            </TabsList>
            <TabsContent value="quote" className="mt-5">
              <FromQuoteForm onSuccess={onSuccess} onCancel={() => onOpenChange(false)} />
            </TabsContent>
            <TabsContent value="direct" className="mt-5">
              <DirectForm onSuccess={onSuccess} onCancel={() => onOpenChange(false)} />
            </TabsContent>
          </Tabs>
        </div>
      </SheetContent>
    </Sheet>
  );
}
