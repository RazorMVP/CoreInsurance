import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type BranchDto, type SbuDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:    z.string().min(2, 'Required'),
  code:    z.string().min(2, 'Required').max(20),
  sbuId:   z.string().optional(),
  address: z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  branch: BranchDto | null; onSuccess: () => void;
}

export default function BranchSheet({ open, onOpenChange, branch, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', code: '', sbuId: '', address: '' },
  });

  // Load SBUs for the parent select
  const sbusQuery = useQuery<SbuDto[]>({
    queryKey: ['setup', 'sbus'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SbuDto[] }>('/api/v1/setup/sbus');
      return res.data.data;
    },
  });
  const sbus = sbusQuery.data ?? [];

  useEffect(() => {
    form.reset(branch ? {
      name: branch.name, code: branch.code,
      sbuId: branch.sbuId ?? '',
      address: branch.address ?? '',
    } : { name: '', code: '', sbuId: '', address: '' });
  }, [branch, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        sbuId: values.sbuId || undefined,
        address: values.address || undefined,
      };
      if (branch) {
        const res = await apiClient.put<{ data: BranchDto }>(`/api/v1/setup/branches/${branch.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: BranchDto }>('/api/v1/setup/branches', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'branches'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: branch ? 'Could not update branch' : 'Could not add branch' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{branch ? 'Edit Branch' : 'Add Branch'}</SheetTitle>
          <SheetDescription>Branches roll up to SBUs. Used on policies for branch-level performance reporting.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Branch Name</FormLabel><FormControl><Input placeholder="e.g. Victoria Island" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="VI" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="sbuId" render={({ field }) => (
              <FormItem>
                <FormLabel>Parent SBU (optional)</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="None — top-level branch" /></SelectTrigger></FormControl>
                  <SelectContent>
                    {sbus.map((s) => (<SelectItem key={s.id} value={s.id}>{s.name} ({s.code})</SelectItem>))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <FormField control={form.control} name="address" render={({ field }) => (
              <FormItem><FormLabel>Address</FormLabel><FormControl><Textarea rows={2} {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : branch ? 'Save Changes' : 'Add Branch'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
