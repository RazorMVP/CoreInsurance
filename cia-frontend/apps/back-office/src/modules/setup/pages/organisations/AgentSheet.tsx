import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type AgentDto, type AgentType } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  code:          z.string().min(2, 'Required').max(20),
  type:          z.enum(['INDIVIDUAL', 'CORPORATE']),
  licenseNumber: z.string().optional(),
  email:         z.string().email().optional().or(z.literal('')),
  phone:         z.string().optional(),
  address:       z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  agent: AgentDto | null; onSuccess: () => void;
}

export default function AgentSheet({ open, onOpenChange, agent, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', code: '', type: 'INDIVIDUAL', licenseNumber: '', email: '', phone: '', address: '' },
  });

  useEffect(() => {
    form.reset(agent ? {
      name: agent.name, code: agent.code, type: agent.type,
      licenseNumber: agent.licenseNumber ?? '',
      email: agent.email ?? '', phone: agent.phone ?? '',
      address: agent.address ?? '',
    } : { name: '', code: '', type: 'INDIVIDUAL', licenseNumber: '', email: '', phone: '', address: '' });
  }, [agent, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        licenseNumber: values.licenseNumber || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
        address: values.address || undefined,
      };
      if (agent) {
        const res = await apiClient.put<{ data: AgentDto }>(`/api/v1/setup/agents/${agent.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: AgentDto }>('/api/v1/setup/agents', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'agents'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: agent ? 'Could not update agent' : 'Could not add agent' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{agent ? 'Edit Agent' : 'Add Agent'}</SheetTitle>
          <SheetDescription>NAICOM-licensed insurance agents represent the insurer and earn commission on policies sold. Individual = a single licensed person; Corporate = a licensed agency firm.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Agent Name</FormLabel><FormControl><Input placeholder="Agent or agency name" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="AGT001" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="type" render={({ field }) => (
                <FormItem><FormLabel>Type</FormLabel>
                  <Select onValueChange={(v) => field.onChange(v as AgentType)} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="INDIVIDUAL">Individual</SelectItem>
                      <SelectItem value="CORPORATE">Corporate</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="licenseNumber" render={({ field }) => (
                <FormItem><FormLabel>NAICOM License</FormLabel><FormControl><Input placeholder="NAICOM agent licence number" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="address" render={({ field }) => (
              <FormItem><FormLabel>Address</FormLabel><FormControl><Textarea rows={2} {...field} /></FormControl><FormMessage /></FormItem>
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
                {save.isPending ? 'Saving…' : agent ? 'Save Changes' : 'Add Agent'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
