import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const EXPENSE_TYPES = [
  'Survey / Assessment Fee',
  'Legal Fee',
  'Medical Report Fee',
  'Towing / Recovery Cost',
  'Investigation Cost',
  'Repair Inspection Fee',
  'Police Report Fee',
  'Other',
];

const schema = z.object({
  type:      z.string().min(2, 'Expense type is required'),
  amount:    z.coerce.number().positive('Amount must be greater than zero'),
  reference: z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimId:      string;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AddExpenseDialog({ open, onOpenChange, claimId, claimNumber, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { type: '', amount: 0, reference: '' },
  });

  const addExpense = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/claims/${claimId}/expenses`, values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      form.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not add expense' }),
  });

  function onSubmit(values: FormValues) {
    addExpense.mutate(values);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) form.reset(); onOpenChange(v); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Add Expense</DialogTitle>
          <DialogDescription>
            Record a claim expense for <span className="font-medium text-foreground">{claimNumber}</span>.
            Expenses can only be modified before the claim is submitted for approval.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField control={form.control} name="type"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Expense Type</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select expense type" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {EXPENSE_TYPES.map(t => <SelectItem key={t} value={t}>{t}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormRow>
              <FormField control={form.control} name="amount"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Amount (₦)</FormLabel>
                    <FormControl><Input type="number" min={0} step={500} {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="reference"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Invoice / Reference (optional)</FormLabel>
                    <FormControl><Input placeholder="e.g. INV-2026-001" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={addExpense.isPending}>
                {addExpense.isPending ? 'Saving…' : 'Add Expense'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
