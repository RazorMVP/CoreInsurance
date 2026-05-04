import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { apiClient, type DebitNoteDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  paymentDate:   z.string().min(1, 'Required'),
  paymentMethod: z.string().min(1, 'Required'),
  reference:     z.string().min(1, 'Required'),
  bankName:      z.string().optional(),
  amount:        z.coerce.number().positive('Must be positive'),
  notes:         z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

const PAYMENT_METHODS = [
  'Bank Transfer', 'Cheque', 'Cash', 'Online Payment', 'USSD', 'Direct Debit',
];

interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  debitNoteIds:  string[];
  bulk:          boolean;
  debitNotes:    DebitNoteDto[];
  onSuccess:     () => void;
}

export default function PostReceiptSheet({ open, onOpenChange, debitNoteIds, bulk, debitNotes, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const selectedNotes = debitNotes.filter(d => debitNoteIds.includes(d.id));
  const totalAmount   = selectedNotes.reduce((s, d) => s + d.outstandingAmount, 0);

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { paymentDate: '', paymentMethod: '', reference: '', bankName: '', amount: totalAmount, notes: '' },
  });

  // Sync amount when the selected debit-note set changes. This MUST be in
  // useEffect — calling form.setValue inside the render body re-renders
  // synchronously and can loop while the user is typing in the amount field.
  useEffect(() => {
    if (totalAmount > 0 && form.getValues('amount') !== totalAmount) {
      form.setValue('amount', totalAmount);
    }
  }, [totalAmount, form]);

  const post = useMutation({
    mutationFn: async (values: FormValues) => {
      const url = bulk ? '/api/v1/finance/receipts/bulk' : '/api/v1/finance/receipts';
      const body = bulk
        ? { debitNoteIds, ...values }
        : { debitNoteId: debitNoteIds[0], ...values };
      const res = await apiClient.post<{ data: { id: string } }>(url, body);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'debit-notes'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      onSuccess();
      form.reset();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not post receipt' }),
  });

  function onSubmit(values: FormValues) {
    post.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{bulk ? 'Bulk Receipt' : 'Post Receipt'}</SheetTitle>
          <SheetDescription>
            {bulk
              ? `Record payment against ${selectedNotes.length} outstanding debit note${selectedNotes.length > 1 ? 's' : ''}.`
              : `Record payment for ${selectedNotes[0]?.debitNoteNumber ?? 'debit note'}.`
            }
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
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-5 space-y-4">
            <FormRow>
              <FormField control={form.control} name="paymentDate"
                render={({ field }) => (<FormItem><FormLabel>Payment Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="amount"
                render={({ field }) => (<FormItem><FormLabel>Amount (₦)</FormLabel><FormControl><Input type="number" min={0} {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="paymentMethod"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Payment Method</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select method" /></SelectTrigger></FormControl>
                    <SelectContent>{PAYMENT_METHODS.map(m => <SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormRow>
              <FormField control={form.control} name="reference"
                render={({ field }) => (<FormItem><FormLabel>Payment Reference</FormLabel><FormControl><Input placeholder="e.g. CHQ-001 / TXN-9876" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="bankName"
                render={({ field }) => (<FormItem><FormLabel>Bank (optional)</FormLabel><FormControl><Input placeholder="e.g. GTBank" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="notes"
              render={({ field }) => (<FormItem><FormLabel>Notes (optional)</FormLabel><FormControl><Input placeholder="Any additional notes" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={post.isPending}>
                {post.isPending ? 'Posting…' : 'Post Receipt'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
