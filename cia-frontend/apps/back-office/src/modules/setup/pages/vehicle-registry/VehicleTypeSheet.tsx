import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleTypeDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  type: VehicleTypeDto | null;
  onSuccess: () => void;
}

export default function VehicleTypeSheet({ open, onOpenChange, type, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });

  useEffect(() => { form.reset(type ? { name: type.name } : { name: '' }); }, [type, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (type) {
        const res = await apiClient.put<{ data: VehicleTypeDto }>(`/api/v1/setup/vehicle-types/${type.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: VehicleTypeDto }>('/api/v1/setup/vehicle-types', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-types'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: type ? 'Could not update type' : 'Could not add type' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{type ? 'Edit Type' : 'Add Type'}</SheetTitle>
          <SheetDescription>Vehicle body types used in motor underwriting.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Type Name</FormLabel><FormControl><Input placeholder="e.g. Saloon / SUV / Truck" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : type ? 'Save Changes' : 'Add Type'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
