import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type RelationshipManagerDto, type BranchDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:     z.string().min(2, 'Required'),
  email:    z.string().email().optional().or(z.literal('')),
  phone:    z.string().optional(),
  branchId: z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  rm: RelationshipManagerDto | null; onSuccess: () => void;
}

export default function RelationshipManagerSheet({ open, onOpenChange, rm, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', email: '', phone: '', branchId: '' },
  });

  // Load branches for the assignment select
  const branchesQuery = useQuery<BranchDto[]>({
    queryKey: ['setup', 'branches'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BranchDto[] }>('/api/v1/setup/branches');
      return res.data.data;
    },
  });
  const branches = branchesQuery.data ?? [];

  useEffect(() => {
    form.reset(rm ? {
      name: rm.name,
      email: rm.email ?? '', phone: rm.phone ?? '',
      branchId: rm.branchId ?? '',
    } : { name: '', email: '', phone: '', branchId: '' });
  }, [rm, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        email: values.email || undefined,
        phone: values.phone || undefined,
        branchId: values.branchId || undefined,
      };
      if (rm) {
        const res = await apiClient.put<{ data: RelationshipManagerDto }>(`/api/v1/setup/relationship-managers/${rm.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: RelationshipManagerDto }>('/api/v1/setup/relationship-managers', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'relationship-managers'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: rm ? 'Could not update relationship manager' : 'Could not add relationship manager' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{rm ? 'Edit Relationship Manager' : 'Add Relationship Manager'}</SheetTitle>
          <SheetDescription>Internal staff who own customer relationships. Assigned at customer onboarding.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Full Name</FormLabel><FormControl><Input placeholder="e.g. Adaora Okonkwo" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <FormField control={form.control} name="branchId" render={({ field }) => (
              <FormItem>
                <FormLabel>Branch (optional)</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="None — head office" /></SelectTrigger></FormControl>
                  <SelectContent>
                    {branches.map((b) => (<SelectItem key={b.id} value={b.id}>{b.name} ({b.code})</SelectItem>))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <FormRow>
              <FormField control={form.control} name="email" render={({ field }) => (
                <FormItem><FormLabel>Email</FormLabel><FormControl><Input type="email" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="phone" render={({ field }) => (
                <FormItem><FormLabel>Phone</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : rm ? 'Save Changes' : 'Add Relationship Manager'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
