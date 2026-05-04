import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const RESERVE_CATEGORIES = [
  'Own Damage — Vehicle Repairs',
  'Own Damage — Property Repairs',
  'Third Party — Bodily Injury',
  'Third Party — Property Damage',
  'Survey / Assessment Fee',
  'Legal Expenses',
  'Ex-gratia Payment',
  'Medical Expenses',
  'Other',
];

const schema = z.object({
  category: z.string().min(2, 'Category is required'),
  amount:   z.coerce.number().positive('Amount must be greater than zero'),
  notes:    z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimId:      string;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AddReserveDialog({ open, onOpenChange, claimId, claimNumber, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { category: '', amount: 0, notes: '' },
  });

  const addReserve = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/claims/${claimId}/reserves`, values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      form.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not add reserve' }),
  });

  function onSubmit(values: FormValues) {
    addReserve.mutate(values);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) form.reset(); onOpenChange(v); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Add Reserve</DialogTitle>
          <DialogDescription>
            Add a new reserve entry for <span className="font-medium text-foreground">{claimNumber}</span>.
            Reserves can only be modified before the claim is submitted for approval.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField control={form.control} name="category"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Reserve Category</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select category" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {RESERVE_CATEGORIES.map(c => <SelectItem key={c} value={c}>{c}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={form.control} name="amount"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Reserve Amount (₦)</FormLabel>
                  <FormControl><Input type="number" min={0} step={5000} {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={form.control} name="notes"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Notes (optional)</FormLabel>
                  <FormControl><Input placeholder="Basis for this reserve amount" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={addReserve.isPending}>
                {addReserve.isPending ? 'Saving…' : 'Add Reserve'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
