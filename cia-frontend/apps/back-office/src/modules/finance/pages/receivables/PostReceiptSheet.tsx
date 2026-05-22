// PostReceiptSheet — finance-tab entry point. Posts against
// /api/v1/debit-notes/{dnId}/receipts (the nested endpoint; backend has no
// flat /receipts POST). Single mode submits one receipt. Bulk mode iterates
// over the selected DNs, posting a receipt of each DN's outstanding amount.
//
// Schema mirrors com.nubeero.cia.finance.dto.PostReceiptRequest:
//   amount, paymentDate, paymentMethod (CASH/CHEQUE/BANK_TRANSFER/...),
//   bankId, bankName, chequeNumber, narration.

import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator, Textarea,
} from '@cia/ui';
import {
  apiClient,
  type BankDto, type DebitNoteDto, type PaymentMethod, type PostReceiptRequest,
} from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'CASH',          label: 'Cash' },
  { value: 'CHEQUE',        label: 'Cheque' },
  { value: 'BANK_TRANSFER', label: 'Bank Transfer' },
  { value: 'DIRECT_DEBIT',  label: 'Direct Debit' },
  { value: 'MOBILE_MONEY',  label: 'Mobile Money' },
  { value: 'POS',           label: 'Point of Sale' },
];

const METHODS_REQUIRING_BANK   = new Set<PaymentMethod>(['CHEQUE', 'BANK_TRANSFER', 'DIRECT_DEBIT', 'POS']);
const METHODS_REQUIRING_CHEQUE = new Set<PaymentMethod>(['CHEQUE']);

// Bulk mode drops the amount field — each DN is settled at its own
// outstanding amount, mirroring how Module 8 surfaces a bulk receipt.
const singleSchema = z.object({
  amount:        z.coerce.number().min(0.01, 'Required'),
  paymentDate:   z.string().min(1, 'Required'),
  paymentMethod: z.enum(['CASH', 'CHEQUE', 'BANK_TRANSFER', 'DIRECT_DEBIT', 'MOBILE_MONEY', 'POS']),
  bankId:        z.string().optional().or(z.literal('')),
  chequeNumber:  z.string().optional().or(z.literal('')),
  narration:     z.string().optional(),
}).superRefine((v, ctx) => {
  if (METHODS_REQUIRING_BANK.has(v.paymentMethod) && !v.bankId) {
    ctx.addIssue({ code: 'custom', path: ['bankId'], message: 'Required for this payment method' });
  }
  if (METHODS_REQUIRING_CHEQUE.has(v.paymentMethod) && !v.chequeNumber) {
    ctx.addIssue({ code: 'custom', path: ['chequeNumber'], message: 'Required for cheque payments' });
  }
});
type FormValues = z.infer<typeof singleSchema>;

interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  debitNoteIds:  string[];
  bulk:          boolean;
  debitNotes:    DebitNoteDto[];
  onSuccess:     () => void;
}

export default function PostReceiptSheet({ open, onOpenChange, debitNoteIds, bulk, debitNotes, onSuccess }: Props) {
  const queryClient   = useQueryClient();
  const selectedNotes = debitNotes.filter(d => debitNoteIds.includes(d.id));
  const totalAmount   = selectedNotes.reduce((s, d) => s + d.outstandingAmount, 0);
  const targetDn      = selectedNotes[0];

  const banksQuery = useQuery<BankDto[]>({
    queryKey: ['setup', 'banks'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BankDto[] }>('/api/v1/setup/banks');
      return res.data.data;
    },
    enabled: open,
  });
  const banks = banksQuery.data ?? [];

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(singleSchema) as any,
    defaultValues: {
      amount:        targetDn?.outstandingAmount ?? 0,
      paymentDate:   new Date().toISOString().slice(0, 10),
      paymentMethod: 'BANK_TRANSFER',
      bankId:        '',
      chequeNumber:  '',
      narration:     '',
    },
  });

  // Re-default whenever the dialog (re-)opens for a new DN set.
  useEffect(() => {
    if (open) {
      form.reset({
        amount:        targetDn?.outstandingAmount ?? 0,
        paymentDate:   new Date().toISOString().slice(0, 10),
        paymentMethod: 'BANK_TRANSFER',
        bankId:        '',
        chequeNumber:  '',
        narration:     '',
      });
    }
  }, [open, targetDn?.id, form]);

  const paymentMethod = form.watch('paymentMethod');
  const needsBank   = METHODS_REQUIRING_BANK.has(paymentMethod);
  const needsCheque = METHODS_REQUIRING_CHEQUE.has(paymentMethod);

  const post = useMutation({
    mutationFn: async (values: FormValues) => {
      const bankName = values.bankId ? banks.find(b => b.id === values.bankId)?.name : undefined;
      const sharedFields = {
        paymentDate:   values.paymentDate,
        paymentMethod: values.paymentMethod,
        bankId:        values.bankId       || undefined,
        bankName,
        chequeNumber:  values.chequeNumber || undefined,
        narration:     values.narration    || undefined,
      } as const;

      if (bulk) {
        // One receipt per DN at its own outstanding amount. Backend has no
        // bulk endpoint; iterating is correct for the operation.
        await Promise.all(selectedNotes.map(dn => {
          const payload: PostReceiptRequest = { amount: dn.outstandingAmount, ...sharedFields };
          return apiClient.post(`/api/v1/debit-notes/${dn.id}/receipts`, payload);
        }));
        return;
      }

      const payload: PostReceiptRequest = { amount: values.amount, ...sharedFields };
      await apiClient.post(`/api/v1/debit-notes/${debitNoteIds[0]}/receipts`, payload);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'debit-notes'] });
      // Detail-side caches read by other surfaces (policy detail, claim detail).
      queryClient.invalidateQueries({ queryKey: ['policy-debit-note'] });
      queryClient.invalidateQueries({ queryKey: ['policy-receipts'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not post receipt' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{bulk ? 'Bulk Receipt' : 'Post Receipt'}</SheetTitle>
          <SheetDescription>
            {bulk
              ? `Settle ${selectedNotes.length} debit note${selectedNotes.length > 1 ? 's' : ''} at full outstanding amount.`
              : `Record payment for ${targetDn?.debitNoteNumber ?? 'debit note'}.`}
          </SheetDescription>
        </SheetHeader>

        {/* Selected debit notes summary */}
        <div className="mt-5 rounded-lg border bg-muted/40 p-4 space-y-2">
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {bulk ? 'Debit Notes' : 'Debit Note'}
          </p>
          {selectedNotes.map((dn) => (
            <div key={dn.id} className="flex items-center justify-between text-sm">
              <div>
                <span className="font-mono text-xs text-primary">{dn.debitNoteNumber}</span>
                <span className="ml-2 text-muted-foreground">{dn.customerName}</span>
              </div>
              <span className="font-medium tabular-nums">₦{dn.outstandingAmount.toLocaleString()}</span>
            </div>
          ))}
          {bulk && selectedNotes.length > 1 && (
            <>
              <Separator />
              <div className="flex items-center justify-between text-sm font-semibold">
                <span>Total</span>
                <span className="text-primary">₦{totalAmount.toLocaleString()}</span>
              </div>
            </>
          )}
        </div>

        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => post.mutate(v))} className="mt-5 space-y-4">
            <FormRow>
              <FormField control={form.control} name="paymentDate" render={({ field }) => (
                <FormItem>
                  <FormLabel>Payment Date</FormLabel>
                  <FormControl><Input type="date" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
              {!bulk && (
                <FormField control={form.control} name="amount" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Amount (₦)</FormLabel>
                    <FormControl><Input type="number" step={0.01} min={0.01} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
              )}
            </FormRow>

            <FormField control={form.control} name="paymentMethod" render={({ field }) => (
              <FormItem>
                <FormLabel>Payment Method</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select method" /></SelectTrigger></FormControl>
                  <SelectContent>
                    {PAYMENT_METHODS.map(m => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            {needsBank && (
              <FormField control={form.control} name="bankId" render={({ field }) => (
                <FormItem>
                  <FormLabel>Bank</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value ?? ''}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select bank" /></SelectTrigger></FormControl>
                    <SelectContent>{banks.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
            )}

            {needsCheque && (
              <FormField control={form.control} name="chequeNumber" render={({ field }) => (
                <FormItem>
                  <FormLabel>Cheque Number</FormLabel>
                  <FormControl><Input placeholder="e.g. 000123456" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
            )}

            <FormField control={form.control} name="narration" render={({ field }) => (
              <FormItem>
                <FormLabel>Narration <span className="text-muted-foreground">(optional)</span></FormLabel>
                <FormControl><Textarea rows={2} className="resize-none" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={post.isPending || selectedNotes.length === 0}>
                {post.isPending ? 'Posting…' : bulk ? `Post ${selectedNotes.length} Receipts` : 'Post Receipt'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
