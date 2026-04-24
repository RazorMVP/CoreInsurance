import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

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
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AddExpenseDialog({ open, onOpenChange, claimNumber, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { type: '', amount: 0, reference: '' },
  });

  async function onSubmit(values: FormValues) {
    console.log('Add expense', values);
    // TODO: POST /api/v1/claims/{id}/expenses
    form.reset();
    onSuccess();
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
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : 'Add Expense'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
