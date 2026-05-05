import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input, Textarea,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

// Backend requires at least one of reportPath / notes — refine catches that.
const schema = z.object({
  reportPath: z.string().optional(),
  notes:      z.string().optional(),
}).refine(
  (v) => (v.reportPath ?? '').trim().length > 0 || (v.notes ?? '').trim().length > 0,
  { message: 'Provide a report file path or notes (or both)', path: ['notes'] },
);
type FormValues = z.infer<typeof schema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimId:      string;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function SubmitInspectionReportDialog({ open, onOpenChange, claimId, claimNumber, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { reportPath: '', notes: '' },
  });

  const submit = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: unknown }>(
        `/api/v1/claims/${claimId}/inspection/report`,
        {
          reportPath: values.reportPath?.trim() || null,
          notes:      values.notes?.trim()      || null,
        },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId, 'inspection'] });
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      form.reset();
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not submit report' }),
  });

  function onSubmit(values: FormValues) {
    submit.mutate(values);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) form.reset(); onOpenChange(v); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Submit Inspection Report</DialogTitle>
          <DialogDescription>
            Record the surveyor's findings for <span className="font-medium text-foreground">{claimNumber}</span>.
            Either link an uploaded report file (storage path) or capture findings as notes —
            you can do both.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField control={form.control} name="reportPath"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Report File Path (optional)</FormLabel>
                  <FormControl>
                    <Input placeholder="claims/CLM-2026-00001/survey-report.pdf" {...field} />
                  </FormControl>
                  <p className="text-xs text-muted-foreground">
                    Storage key. Upload the file via the Documents tab, then paste its path here.
                  </p>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={form.control} name="notes"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Inspection Notes (optional)</FormLabel>
                  <FormControl>
                    <Textarea
                      rows={5}
                      placeholder="Findings, recommended reserve adjustment, salvage value, etc."
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={submit.isPending}>
                {submit.isPending ? 'Submitting…' : 'Submit Report'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
