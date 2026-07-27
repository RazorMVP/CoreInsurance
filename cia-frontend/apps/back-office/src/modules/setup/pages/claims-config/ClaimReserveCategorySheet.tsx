import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ClaimReserveCategoryDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required').max(100),
  code: z.string().min(2, 'Required').max(20),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  category: ClaimReserveCategoryDto | null;
  onSuccess: () => void;
}

export default function ClaimReserveCategorySheet({ open, onOpenChange, category, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });

  useEffect(() => { form.reset(category ? { name: category.name, code: category.code } : { name: '', code: '' }); }, [category, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (category) {
        const res = await apiClient.put<{ data: ClaimReserveCategoryDto }>(`/api/v1/setup/claim-reserve-categories/${category.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ClaimReserveCategoryDto }>('/api/v1/setup/claim-reserve-categories', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'claim-reserve-categories'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: category ? 'Could not update category' : 'Could not add category' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{category ? 'Edit Reserve Category' : 'Add Reserve Category'}</SheetTitle>
          <SheetDescription>Reserve categories bucket claim reserves for reporting.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Bodily Injury" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="BI" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : category ? 'Save Changes' : 'Add Category'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
