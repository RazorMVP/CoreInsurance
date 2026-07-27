import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type NatureOfLossDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100), code: z.string().min(2, 'Required').max(20) });
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  nature: NatureOfLossDto | null;
  onSuccess: () => void;
}

export default function NatureOfLossSheet({ open, onOpenChange, nature, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });

  useEffect(() => { form.reset(nature ? { name: nature.name, code: nature.code } : { name: '', code: '' }); }, [nature, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (nature) {
        const res = await apiClient.put<{ data: NatureOfLossDto }>(`/api/v1/setup/nature-of-loss/${nature.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: NatureOfLossDto }>('/api/v1/setup/nature-of-loss', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'nature-of-loss'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: nature ? 'Could not update nature' : 'Could not add nature' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{nature ? 'Edit Nature of Loss' : 'Add Nature of Loss'}</SheetTitle>
          <SheetDescription>High-level loss categories (Fire, Motor Accident, Theft…).</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Fire" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="FIRE" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : nature ? 'Save Changes' : 'Add Nature'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
