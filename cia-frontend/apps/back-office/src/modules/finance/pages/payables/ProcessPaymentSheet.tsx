import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Separator,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, type CreditNoteDto } from '@cia/api-client';

const schema = z.object({
  amount:        z.coerce.number().positive('Amount must be greater than zero'),
  paymentMethod: z.enum(['BANK_TRANSFER', 'CHEQUE', 'CASH', 'ONLINE']),
  bankName:      z.string().min(1, 'Bank name is required'),
  reference:     z.string().min(1, 'Enter the payment reference or transaction ID'),
  notes:         z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

const SOURCE_LABELS: Record<CreditNoteDto['sourceType'], string> = {
  CLAIM:       'Claim DV',
  ENDORSEMENT: 'Endorsement',
  COMMISSION:  'Commission',
  REINSURANCE: 'RI FAC',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  creditNote:   CreditNoteDto | null;
  onSuccess:    () => void;
}

export default function ProcessPaymentSheet({ open, onOpenChange, creditNote, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { amount: creditNote?.amount ?? 0, paymentMethod: 'BANK_TRANSFER', bankName: '', reference: '', notes: '' },
    values:        { amount: creditNote?.amount ?? 0, paymentMethod: 'BANK_TRANSFER', bankName: '', reference: '', notes: '' },
  });

  const process = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        '/api/v1/finance/payments',
        { creditNoteId: creditNote?.id, ...values },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'credit-notes'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'payments'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: FormValues) {
    process.mutate(values);
  }

  if (!creditNote) return null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Process Payment</SheetTitle>
          <SheetDescription>
            Record the payment details for{' '}
            <span className="font-medium text-foreground">{creditNote.number}</span>
            {' '}({SOURCE_LABELS[creditNote.sourceType]}).
          </SheetDescription>
        </SheetHeader>

        {/* Credit note summary */}
        <div className="mt-4 rounded-lg bg-muted/40 p-3 space-y-1 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Credit Note</span>
            <span className="font-mono text-xs text-primary">{creditNote.number}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Source</span>
            <span className="font-medium">{SOURCE_LABELS[creditNote.sourceType]}</span>
          </div>
          <div className="flex justify-between font-semibold">
            <span className="text-muted-foreground">Amount</span>
            <span className="text-primary">₦{creditNote.amount.toLocaleString()}</span>
          </div>
        </div>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-4 space-y-4">
            <Separator />

            <FormField control={form.control} name="amount"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Payment Amount (₦)</FormLabel>
                  <FormControl>
                    <Input type="number" min={0} step={500} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="paymentMethod"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Payment Method</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select method" /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="BANK_TRANSFER">Bank Transfer</SelectItem>
                      <SelectItem value="CHEQUE">Cheque</SelectItem>
                      <SelectItem value="CASH">Cash</SelectItem>
                      <SelectItem value="ONLINE">Online Payment</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormRow>
              <FormField control={form.control} name="bankName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Bank Name</FormLabel>
                    <FormControl><Input placeholder="e.g. GTBank" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="reference"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Reference / Transaction ID</FormLabel>
                    <FormControl><Input placeholder="e.g. TXN123456789" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormField control={form.control} name="notes"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Notes (optional)</FormLabel>
                  <FormControl><Input placeholder="Any additional notes" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

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
