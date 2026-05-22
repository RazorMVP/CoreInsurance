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
import {
  apiClient,
  type AgentDto,
  type BrokerDto,
  type CustomerDto,
  type ProductDto,
  type QuoteDto,
} from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

type CustomerSummary = CustomerDto & { firstName?: string; lastName?: string; companyName?: string };
function customerLabel(c: CustomerSummary): string {
  if (c.customerType === 'CORPORATE') return c.companyName ?? '(unnamed corporate)';
  return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || '(unnamed individual)';
}
type ProductWithRate = ProductDto & { productRate?: number };

// ── Convert from quote ─────────────────────────────────────────────────────
// The bind-from-quote endpoint takes a path parameter only — no body. Every
// field on the bound policy (businessType, broker, dates, risks, premium)
// comes from the source quote. Earlier versions of this form captured a
// businessType / paymentTerms / notes triple in a body that the backend
// silently dropped, which was a UX lie. Slice 91 trims the schema to the
// single input the operation actually needs.
const fromQuoteSchema = z.object({
  quoteId: z.string().min(1, 'Select an approved quote'),
});
type FromQuoteValues = z.infer<typeof fromQuoteSchema>;

// ── Create without quote ──────────────────────────────────────────────────
// Schema fields map 1:1 to com.nubeero.cia.policy.dto.PolicyRequest with two
// UI-only exceptions:
//   - `channel` + `intermediaryId` → resolved at submit to brokerId / agentId.
//   - `rate` is preview-only — backend computes premium server-side from
//     product.rate × risk.sumInsured. Sent value would be ignored.
// At submit we also compose a single-row risks array (description from the
// selected product, sumInsured from the form). Users refine the risk schedule
// via RisksEditorDialog on the policy detail page once the policy exists.
const directSchema = z.object({
  customerId:        z.string().min(1, 'Required'),
  productId:         z.string().min(1, 'Required'),
  channel:           z.enum(['DIRECT', 'BROKER', 'AGENT']),
  intermediaryId:    z.string().optional().or(z.literal('')),
  businessType:      z.enum(['DIRECT', 'DIRECT_WITH_COINSURANCE', 'INWARD_COINSURANCE']),
  policyStartDate:   z.string().min(1, 'Required'),
  policyEndDate:     z.string().min(1, 'Required'),
  sumInsured:        z.coerce.number().positive(),
  rate:              z.coerce.number().min(0),
  discount:          z.coerce.number().min(0),
}).refine(
  (v) => v.channel === 'DIRECT' || (v.intermediaryId && v.intermediaryId.length > 0),
  { message: 'Select an intermediary', path: ['intermediaryId'] },
);
type DirectValues = z.infer<typeof directSchema>;

const CHANNELS = [
  { value: 'DIRECT', label: 'Direct (no intermediary)' },
  { value: 'BROKER', label: 'Broker' },
  { value: 'AGENT',  label: 'Agent' },
] as const;

const BUSINESS_TYPES = [
  { value: 'DIRECT',                   label: 'Direct' },
  { value: 'DIRECT_WITH_COINSURANCE',  label: 'Direct with Coinsurance' },
  { value: 'INWARD_COINSURANCE',       label: 'Inward Coinsurance' },
];
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
  const approvedQuotes = (quotesQuery.data ?? []).map(q => {
    // Surface the quote's businessType + brokerName (when set) in the option
    // label so the bind confirmation step is visible at picker time. Earlier
    // versions duplicated businessType as an editable form field; the bind
    // endpoint takes nothing but the quote ID, so showing it as inline
    // confirmation here matches what's actually about to happen.
    const businessLabel = q.businessType.replace(/_/g, ' ').toLowerCase();
    const extras = [`₦${(q.netPremium ?? 0).toLocaleString()}`, businessLabel];
    if (q.brokerName) extras.push(`Broker: ${q.brokerName}`);
    return {
      id:    q.id,
      label: `${q.quoteNumber} — ${q.customerName} · ${q.productName} · ${extras.join(' · ')}`,
    };
  });

  const form = useForm<FromQuoteValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(fromQuoteSchema) as any,
    defaultValues: { quoteId: '' },
  });

  const bind = useMutation({
    mutationFn: async (values: FromQuoteValues) => {
      // POST takes no body — the bind copies everything off the quote
      // (businessType, customer, broker, dates, risks, premium).
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/policies/bind-from-quote/${values.quoteId}`,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not bind policy from quote' }),
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
        <p className="text-xs text-muted-foreground">
          Business type, broker attribution, period, risks, and premium are copied from the selected quote.
          Use the policy detail page to refine the schedule after the policy is issued.
        </p>
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
    defaultValues: {
      customerId: '', productId: '', channel: 'DIRECT', intermediaryId: '',
      businessType: 'DIRECT', policyStartDate: '', policyEndDate: '',
      sumInsured: 0, rate: 0, discount: 0,
    },
  });

  // Lazy-load intermediary lists — only fetched when the user picks that channel.
  const channel = form.watch('channel');
  const brokersQuery = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BrokerDto[] }>('/api/v1/setup/brokers');
      return res.data.data;
    },
    enabled: channel === 'BROKER',
  });
  const agentsQuery = useQuery<AgentDto[]>({
    queryKey: ['setup', 'agents'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: AgentDto[] }>('/api/v1/setup/agents');
      return res.data.data;
    },
    enabled: channel === 'AGENT',
  });
  const brokers = brokersQuery.data ?? [];
  const agents  = agentsQuery.data  ?? [];

  function onChannelChange(value: string, fn: (v: string) => void) {
    fn(value);
    form.setValue('intermediaryId', ''); // clear stale selection when switching
  }

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
      // Compose a payload matching com.nubeero.cia.policy.dto.PolicyRequest.
      // Transform handles three concerns:
      //   1. channel + intermediaryId → brokerId / agentId (Slice 89 UX,
      //      backed by V53 XOR + service-layer BROKER_AGENT_EXCLUSIVE guard).
      //   2. Strip rate (UI preview only — backend computes premium server-side
      //      from product.rate × risk.sumInsured, never reads the request rate).
      //   3. Compose a single-row risks array — backend @NotEmpty requires it,
      //      and PolicyRiskRequest.description is NotBlank. Description
      //      auto-fills from the selected product so users can issue the policy
      //      with one click and refine the schedule via RisksEditorDialog on
      //      the detail page after creation.
      const { channel: ch, intermediaryId, rate: _previewRate, sumInsured, ...rest } = values;
      const product = products.find(p => p.id === values.productId);
      const riskDescription = product?.name ?? 'Risk';
      const payload: Record<string, unknown> = {
        ...rest,
        risks: [{ description: riskDescription, sumInsured }],
      };
      if (ch === 'BROKER' && intermediaryId) payload.brokerId = intermediaryId;
      if (ch === 'AGENT'  && intermediaryId) payload.agentId  = intermediaryId;
      const res = await apiClient.post<{ data: { id: string } }>('/api/v1/policies', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not create policy' }),
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
          <FormField control={form.control} name="channel"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Channel</FormLabel>
                <Select onValueChange={(v) => onChannelChange(v, field.onChange)} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                  <SelectContent>{CHANNELS.map(c => <SelectItem key={c.value} value={c.value}>{c.label}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
          {channel !== 'DIRECT' && (
            <FormField control={form.control} name="intermediaryId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{channel === 'BROKER' ? 'Broker' : 'Agent'}</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value ?? ''}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder={channel === 'BROKER' ? 'Select broker' : 'Select agent'} />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {channel === 'BROKER'
                        ? brokers.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)
                        : agents.map(a  => <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
          )}
        </FormRow>
        <FormRow>
          <FormField control={form.control} name="policyStartDate" render={({ field }) => (<FormItem><FormLabel>Start Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
          <FormField control={form.control} name="policyEndDate"   render={({ field }) => (<FormItem><FormLabel>End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
        </FormRow>
        <FormRow>
          <FormField control={form.control} name="sumInsured"   render={({ field }) => (<FormItem><FormLabel>Sum Insured (₦)</FormLabel><FormControl><Input type="number" {...field} /></FormControl><FormMessage /></FormItem>)} />
          <FormField control={form.control} name="rate"         render={({ field }) => (<FormItem><FormLabel>Rate (%)</FormLabel><FormControl><Input type="number" step={0.01} {...field} /></FormControl><FormMessage /></FormItem>)} />
        </FormRow>
        <FormRow>
          <FormField control={form.control} name="discount"     render={({ field }) => (<FormItem><FormLabel>Discount (₦)</FormLabel><FormControl><Input type="number" {...field} /></FormControl><FormMessage /></FormItem>)} />
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
