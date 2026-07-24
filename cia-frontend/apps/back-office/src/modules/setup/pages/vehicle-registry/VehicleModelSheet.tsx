import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleModelDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(1, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  makeId: string;
  makeName: string;
  model: VehicleModelDto | null;
  onSuccess: () => void;
}

export default function VehicleModelSheet({ open, onOpenChange, makeId, makeName, model, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });

  useEffect(() => { form.reset(model ? { name: model.name } : { name: '' }); }, [model, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const base = `/api/v1/setup/vehicle-makes/${makeId}/models`;
      if (model) {
        const res = await apiClient.put<{ data: VehicleModelDto }>(`${base}/${model.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: VehicleModelDto }>(base, values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-models', makeId] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: model ? 'Could not update model' : 'Could not add model' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{model ? 'Edit Model' : 'Add Model'}</SheetTitle>
          <SheetDescription>Model of <span className="font-medium">{makeName}</span>.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Model Name</FormLabel><FormControl><Input placeholder="e.g. Camry" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : model ? 'Save Changes' : 'Add Model'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
