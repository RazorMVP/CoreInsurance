import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type CauseOfLossDto, type NatureOfLossDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required').max(100),
  code: z.string().min(2, 'Required').max(20),
  natureOfLossId: z.string().min(1, 'Select a nature of loss'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  cause: CauseOfLossDto | null;
  onSuccess: () => void;
}

export default function CauseOfLossSheet({ open, onOpenChange, cause, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '', natureOfLossId: '' } });

  const naturesQuery = useQuery<NatureOfLossDto[]>({
    queryKey: ['setup', 'nature-of-loss'],
    queryFn: async () => (await apiClient.get<{ data: NatureOfLossDto[] }>('/api/v1/setup/nature-of-loss')).data.data,
  });
  const natures = naturesQuery.data ?? [];

  useEffect(() => {
    form.reset(cause ? { name: cause.name, code: cause.code, natureOfLossId: cause.natureOfLossId } : { name: '', code: '', natureOfLossId: '' });
  }, [cause, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (cause) {
        const res = await apiClient.put<{ data: CauseOfLossDto }>(`/api/v1/setup/cause-of-loss/${cause.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: CauseOfLossDto }>('/api/v1/setup/cause-of-loss', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'cause-of-loss'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: cause ? 'Could not update cause' : 'Could not add cause' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{cause ? 'Edit Cause of Loss' : 'Add Cause of Loss'}</SheetTitle>
          <SheetDescription>Specific causes under a nature of loss (drives the claim cascading dropdown).</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Electrical Fault" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="ELEC" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="natureOfLossId" render={({ field }) => (
              <FormItem>
                <FormLabel>Nature of Loss</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select a nature…" /></SelectTrigger></FormControl>
                  <SelectContent>{natures.map((n) => (<SelectItem key={n.id} value={n.id}>{n.name} ({n.code})</SelectItem>))}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : cause ? 'Save Changes' : 'Add Cause'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
