import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useFieldArray, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type ClassOfBusinessDto } from '@cia/api-client';
import { z } from 'zod';

interface ReinsurerDto { id: string; name: string; }

const reinsurersSchema = z.object({
  reinsurerId: z.string().min(1, 'Required'),
  share:       z.coerce.number().min(1).max(100),
});

const schema = z.object({
  name:            z.string().min(2, 'Required'),
  type:            z.enum(['SURPLUS', 'QUOTA_SHARE', 'XOL']),
  classOfBusiness: z.string().min(1, 'Required'),
  year:            z.coerce.number().min(2020).max(2030),
  retentionLimit:  z.coerce.number().min(0),
  treatyLimit:     z.coerce.number().min(0),
  reinsurers:      z.array(reinsurersSchema).min(1, 'Add at least one reinsurer'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  treaty: any | null; onSuccess: () => void;
}

export default function TreatySheet({ open, onOpenChange, treaty, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>('/api/v1/setup/classes-of-business');
      return res.data.data;
    },
    enabled: open,
  });
  const classes = classesQuery.data ?? [];

  const reinsurersQuery = useQuery<ReinsurerDto[]>({
    queryKey: ['setup', 'reinsurance-companies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReinsurerDto[] }>('/api/v1/setup/reinsurance-companies');
      return res.data.data;
    },
    enabled: open,
  });
  const reinsurers = reinsurersQuery.data ?? [];

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      name: treaty?.name ?? '', type: treaty?.type ?? 'SURPLUS',
      classOfBusiness: treaty?.classOfBusiness ?? '', year: treaty?.year ?? new Date().getFullYear(),
      retentionLimit: treaty?.retentionLimit ?? 0, treatyLimit: treaty?.treatyLimit ?? 0,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      reinsurers: treaty?.reinsurers?.map((r: any) => ({ reinsurerId: r.name, share: r.share })) ?? [{ reinsurerId: '', share: 100 }],
    },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'reinsurers' });
  const treatyType = form.watch('type');
  const totalShare = form.watch('reinsurers').reduce((s, r) => s + (r.share || 0), 0);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (treaty?.id) {
        const res = await apiClient.put<{ data: { id: string } }>(
          `/api/v1/reinsurance/treaties/${treaty.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: { id: string } }>(
        '/api/v1/reinsurance/treaties', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reinsurance', 'treaties'] });
      onSuccess();
    },
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{treaty ? 'Edit Treaty' : 'New Treaty'}</SheetTitle>
          <SheetDescription>Configure the treaty type, class of business, limits and participating reinsurers.</SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormField control={form.control} name="name"
              render={({ field }) => (<FormItem><FormLabel>Treaty Name</FormLabel><FormControl><Input placeholder="e.g. Motor Surplus Treaty 2026" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="type"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Treaty Type</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                      <SelectContent>
                        <SelectItem value="SURPLUS">Surplus</SelectItem>
                        <SelectItem value="QUOTA_SHARE">Quota Share</SelectItem>
                        <SelectItem value="XOL">Excess of Loss (XOL)</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="year"
                render={({ field }) => (<FormItem><FormLabel>Treaty Year</FormLabel><FormControl><Input type="number" min={2020} max={2030} {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="classOfBusiness"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Class of Business</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select class" /></SelectTrigger></FormControl>
                    <SelectContent>{classes.map(c => <SelectItem key={c.id} value={c.name}>{c.name}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {treatyType !== 'QUOTA_SHARE' && (
              <FormRow>
                <FormField control={form.control} name="retentionLimit"
                  render={({ field }) => (<FormItem><FormLabel>Retention Limit (₦)</FormLabel><FormControl><Input type="number" min={0} {...field} /></FormControl><FormMessage /></FormItem>)} />
                <FormField control={form.control} name="treatyLimit"
                  render={({ field }) => (<FormItem><FormLabel>{treatyType === 'XOL' ? 'XOL Limit (₦)' : 'Surplus Limit (₦)'}</FormLabel><FormControl><Input type="number" min={0} {...field} /></FormControl><FormMessage /></FormItem>)} />
              </FormRow>
            )}

            {treatyType === 'QUOTA_SHARE' && (
              <div className="rounded-lg bg-muted/40 p-3 text-xs text-muted-foreground">
                Quota Share treaties split every risk by fixed percentages. Define the insurer/reinsurer shares below (must sum to 100%).
              </div>
            )}

            <Separator />

            {/* Reinsurer participants */}
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-sm font-semibold text-foreground">Participating Reinsurers</p>
                <span className={`text-xs font-medium ${totalShare === 100 ? 'text-primary' : 'text-destructive'}`}>
                  {totalShare}% of 100%
                </span>
              </div>
              {fields.map((f, i) => (
                <div key={f.id} className="flex items-end gap-3">
                  <FormField control={form.control} name={`reinsurers.${i}.reinsurerId`}
                    render={({ field }) => (
                      <FormItem className="flex-1">
                        {i === 0 && <FormLabel>Reinsurer</FormLabel>}
                        <Select onValueChange={field.onChange} value={field.value}>
                          <FormControl><SelectTrigger><SelectValue placeholder="Select reinsurer" /></SelectTrigger></FormControl>
                          <SelectContent>{reinsurers.map(r => <SelectItem key={r.id} value={r.name}>{r.name}</SelectItem>)}</SelectContent>
                        </Select>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField control={form.control} name={`reinsurers.${i}.share`}
                    render={({ field }) => (
                      <FormItem className="w-24">
                        {i === 0 && <FormLabel>Share %</FormLabel>}
                        <FormControl><Input type="number" min={1} max={100} {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  {fields.length > 1 && (
                    <Button type="button" variant="ghost" size="sm" className="h-9 text-destructive shrink-0" onClick={() => remove(i)}>✕</Button>
                  )}
                </div>
              ))}
              <Button type="button" variant="outline" size="sm"
                onClick={() => append({ reinsurerId: '', share: 0 })}>
                + Add Reinsurer
              </Button>
            </div>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending || totalShare !== 100}>
                {save.isPending ? 'Saving…' : treaty ? 'Save Changes' : 'Create Treaty'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
