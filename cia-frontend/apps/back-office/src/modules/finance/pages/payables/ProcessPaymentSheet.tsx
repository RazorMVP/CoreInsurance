// ProcessPaymentSheet — posts against
// /api/v1/credit-notes/{cnId}/payments. Schema mirrors
// com.nubeero.cia.finance.dto.PostPaymentRequest:
//   amount, paymentDate, paymentMethod, bankId, bankName,
//   bankAccountName, bankAccountNumber, narration.

import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Separator,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  apiClient,
  type BankDto, type CreditNoteDto, type FinanceEntityType, type PaymentMethod,
  type PostPaymentRequest,
} from '@cia/api-client';
import { applyApiErrors } from '@/lib/form-errors';

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'CASH',          label: 'Cash' },
  { value: 'CHEQUE',        label: 'Cheque' },
  { value: 'BANK_TRANSFER', label: 'Bank Transfer' },
  { value: 'DIRECT_DEBIT',  label: 'Direct Debit' },
  { value: 'MOBILE_MONEY',  label: 'Mobile Money' },
  { value: 'POS',           label: 'Point of Sale' },
];

const METHODS_REQUIRING_BANK = new Set<PaymentMethod>(['CHEQUE', 'BANK_TRANSFER', 'DIRECT_DEBIT', 'POS']);

const ENTITY_LABELS: Record<FinanceEntityType, string> = {
  POLICY:        'Policy',
  ENDORSEMENT:   'Endorsement',
  CLAIM:         'Claim DV',
  CLAIM_EXPENSE: 'Claim Expense',
  COMMISSION:    'Commission',
  REINSURANCE:   'RI FAC',
};

const schema = z.object({
  amount:            z.coerce.number().min(0.01, 'Amount must be greater than zero'),
  paymentDate:       z.string().min(1, 'Required'),
  paymentMethod:     z.enum(['CASH', 'CHEQUE', 'BANK_TRANSFER', 'DIRECT_DEBIT', 'MOBILE_MONEY', 'POS']),
  bankId:            z.string().optional().or(z.literal('')),
  bankAccountName:   z.string().optional(),
  bankAccountNumber: z.string().optional(),
  narration:         z.string().optional(),
}).superRefine((v, ctx) => {
  if (METHODS_REQUIRING_BANK.has(v.paymentMethod) && !v.bankId) {
    ctx.addIssue({ code: 'custom', path: ['bankId'], message: 'Required for this payment method' });
  }
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  creditNote:   CreditNoteDto | null;
  onSuccess:    () => void;
}

export default function ProcessPaymentSheet({ open, onOpenChange, creditNote, onSuccess }: Props) {
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
    resolver: zodResolver(schema) as any,
    defaultValues: {
      amount:            0,
      paymentDate:       new Date().toISOString().slice(0, 10),
      paymentMethod:     'BANK_TRANSFER',
      bankId:            '',
      bankAccountName:   '',
      bankAccountNumber: '',
      narration:         '',
    },
  });

  // Default amount to the outstanding balance whenever the sheet re-opens
  // for a (potentially different) credit note.
  useEffect(() => {
    if (open && creditNote) {
      form.reset({
        amount:            creditNote.outstandingAmount,
        paymentDate:       new Date().toISOString().slice(0, 10),
        paymentMethod:     'BANK_TRANSFER',
        bankId:            '',
        bankAccountName:   '',
        bankAccountNumber: '',
        narration:         '',
      });
    }
  }, [open, creditNote, form]);

  const paymentMethod = form.watch('paymentMethod');
  const needsBank     = METHODS_REQUIRING_BANK.has(paymentMethod);

  const process = useMutation({
    mutationFn: async (values: FormValues) => {
      const bankName = values.bankId ? banks.find(b => b.id === values.bankId)?.name : undefined;
      const payload: PostPaymentRequest = {
        amount:            values.amount,
        paymentDate:       values.paymentDate,
        paymentMethod:     values.paymentMethod,
        bankId:            values.bankId            || undefined,
        bankName,
        bankAccountName:   values.bankAccountName   || undefined,
        bankAccountNumber: values.bankAccountNumber || undefined,
        narration:         values.narration         || undefined,
      };
      await apiClient.post(`/api/v1/credit-notes/${creditNote!.id}/payments`, payload);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'credit-notes'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not process payment' }),
  });

  if (!creditNote) return null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Process Payment</SheetTitle>
          <SheetDescription>
            Record the payment details for{' '}
            <span className="font-medium text-foreground">{creditNote.creditNoteNumber}</span>
            {' '}({ENTITY_LABELS[creditNote.entityType]}).
          </SheetDescription>
        </SheetHeader>

        {/* Credit note summary */}
        <div className="mt-4 rounded-lg bg-muted/40 p-3 space-y-1 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Credit Note</span>
            <span className="font-mono text-xs text-primary">{creditNote.creditNoteNumber}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Source</span>
            <span className="font-medium">{ENTITY_LABELS[creditNote.entityType]}</span>
          </div>
          <div className="flex justify-between font-semibold">
            <span className="text-muted-foreground">Amount Outstanding</span>
            <span className="text-primary">₦{creditNote.outstandingAmount.toLocaleString()}</span>
          </div>
        </div>

        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => process.mutate(v))} className="mt-4 space-y-4">
            <Separator />

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
                  <FormControl><SelectTrigger><SelectValue placeholder="Select method" /></SelectTrigger></FormControl>
                  <SelectContent>
                    {PAYMENT_METHODS.map(m => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            {needsBank && (
              <>
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

                <FormRow>
                  <FormField control={form.control} name="bankAccountName" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Account Name <span className="text-muted-foreground">(optional)</span></FormLabel>
                      <FormControl><Input placeholder="Beneficiary on the account" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />
                  <FormField control={form.control} name="bankAccountNumber" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Account Number <span className="text-muted-foreground">(optional)</span></FormLabel>
                      <FormControl><Input placeholder="e.g. 0123456789" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />
                </FormRow>
              </>
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
              <Button type="submit" disabled={process.isPending}>
                {process.isPending ? 'Processing…' : 'Confirm Payment'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
