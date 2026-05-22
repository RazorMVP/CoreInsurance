import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Skeleton,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type SurveyorDto, type SurveyorType } from '@cia/api-client';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  surveyorType: z.enum(['INTERNAL', 'EXTERNAL']),
  surveyorId:   z.string().min(1, 'Pick a surveyor'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimId:      string;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AssignInspectorDialog({ open, onOpenChange, claimId, claimNumber, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { surveyorType: 'INTERNAL', surveyorId: '' },
  });

  const surveyorType = form.watch('surveyorType');

  const surveyorsQuery = useQuery<SurveyorDto[]>({
    queryKey: ['setup', 'surveyors'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SurveyorDto[] }>(
        '/api/v1/setup/surveyors',
        { params: { size: 200 } },
      );
      return res.data.data ?? [];
    },
    enabled: open,
  });

  const surveyors = (surveyorsQuery.data ?? []).filter(s => s.type === surveyorType);

  const assign = useMutation({
    mutationFn: async (values: FormValues) => {
      const surveyor = surveyors.find(s => s.id === values.surveyorId);
      if (!surveyor) throw new Error('Surveyor not in current list');
      const res = await apiClient.post<{ data: unknown }>(
        `/api/v1/claims/${claimId}/inspection/assign`,
        {
          surveyorType: values.surveyorType,
          surveyorId:   values.surveyorId,
          surveyorName: surveyor.name,
        },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId, 'inspection'] });
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      form.reset({ surveyorType: 'INTERNAL', surveyorId: '' });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: 'Could not assign inspector' }),
  });

  function onSubmit(values: FormValues) {
    assign.mutate(values);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) form.reset({ surveyorType: 'INTERNAL', surveyorId: '' }); onOpenChange(v); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Assign Inspector</DialogTitle>
          <DialogDescription>
            Assign a surveyor to inspect the loss for <span className="font-medium text-foreground">{claimNumber}</span>.
            Internal surveyors are staff; External are independent firms in the registry.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField control={form.control} name="surveyorType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Surveyor Type</FormLabel>
                  <div className="flex gap-2">
                    {(['INTERNAL', 'EXTERNAL'] as SurveyorType[]).map((t) => (
                      <button
                        key={t}
                        type="button"
                        onClick={() => { field.onChange(t); form.setValue('surveyorId', ''); }}
                        className={`flex-1 rounded-md border px-3 py-2 text-sm transition-colors ${field.value === t ? 'bg-teal-50 border-primary font-medium' : 'bg-card hover:bg-muted/40'}`}
                      >
                        {t === 'INTERNAL' ? 'Internal' : 'External'}
                      </button>
                    ))}
                  </div>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={form.control} name="surveyorId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Surveyor</FormLabel>
                  {surveyorsQuery.isLoading ? (
                    <Skeleton className="h-9 w-full" />
                  ) : (
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder={surveyors.length === 0
                            ? `No ${surveyorType.toLowerCase()} surveyors registered`
                            : 'Select a surveyor'} />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {surveyors.map(s => (
                          <SelectItem key={s.id} value={s.id}>
                            {s.name}{s.licenseNumber ? ` · ${s.licenseNumber}` : ''}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={assign.isPending || surveyors.length === 0}>
                {assign.isPending ? 'Assigning…' : 'Assign Inspector'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
