// PostReceiptDialog — POST /api/v1/debit-notes/{dnId}/receipts.
//
// Slice 96 / Backlog C1: replaces the mock "Post Receipt" button on the policy
// detail Finance tab. Mirrors backend com.nubeero.cia.finance.dto.PostReceiptRequest
// — amount + paymentDate + paymentMethod required; bank* + chequeNumber +
// narration optional with their relevance gated by paymentMethod.

import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Textarea,
} from '@cia/ui';
import {
  apiClient,
  type BankDto, type DebitNoteDto, type PaymentMethod, type PostReceiptRequest,
} from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

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

const schema = z.object({
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

type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  debitNote: DebitNoteDto | null;
  onSuccess: () => void;
}

export default function PostReceiptDialog({ open, onOpenChange, debitNote, onSuccess }: Props) {
  const queryClient = useQueryClient();

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
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      amount:        0,
      paymentDate:   new Date().toISOString().slice(0, 10),
      paymentMethod: 'BANK_TRANSFER',
      bankId:        '',
      chequeNumber:  '',
      narration:     '',
    },
  });

  // Default amount to the outstanding balance whenever the dialog re-opens
  // for a (potentially different) debit note.
  useEffect(() => {
    if (open && debitNote) {
      form.reset({
        amount:        debitNote.outstandingAmount,
        paymentDate:   new Date().toISOString().slice(0, 10),
        paymentMethod: 'BANK_TRANSFER',
        bankId:        '',
        chequeNumber:  '',
        narration:     '',
      });
    }
  }, [open, debitNote, form]);

  const paymentMethod = form.watch('paymentMethod');
  const needsBank   = METHODS_REQUIRING_BANK.has(paymentMethod);
  const needsCheque = METHODS_REQUIRING_CHEQUE.has(paymentMethod);

  const post = useMutation({
    mutationFn: async (values: FormValues) => {
      const bankName = values.bankId ? banks.find(b => b.id === values.bankId)?.name : undefined;
      const payload: PostReceiptRequest = {
        amount:        values.amount,
        paymentDate:   values.paymentDate,
        paymentMethod: values.paymentMethod,
        bankId:        values.bankId        || undefined,
        bankName:      bankName,
        chequeNumber:  values.chequeNumber  || undefined,
        narration:     values.narration     || undefined,
      };
      await apiClient.post(`/api/v1/debit-notes/${debitNote!.id}/receipts`, payload);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policy-debit-note', debitNote?.entityId] });
      queryClient.invalidateQueries({ queryKey: ['policy-receipts', debitNote?.id] });
      onSuccess();
      onOpenChange(false);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Post Receipt</DialogTitle>
          <DialogDescription>
            {debitNote
              ? `Against ${debitNote.debitNoteNumber} — outstanding ₦${debitNote.outstandingAmount.toLocaleString()}`
              : 'No debit note selected.'}
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => post.mutate(v))} className="space-y-4">
            <FormRow>
              <FormField control={form.control} name="amount" render={({ field }) => (
                <FormItem>
                  <FormLabel>Amount (₦)</FormLabel>
                  <FormControl><Input type="number" step={0.01} min={0.01} {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="paymentDate" render={({ field }) => (
                <FormItem>
                  <FormLabel>Payment Date</FormLabel>
                  <FormControl><Input type="date" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
            </FormRow>

            <FormField control={form.control} name="paymentMethod" render={({ field }) => (
              <FormItem>
                <FormLabel>Payment Method</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
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

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={post.isPending || !debitNote}>
                {post.isPending ? 'Posting…' : 'Post Receipt'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
