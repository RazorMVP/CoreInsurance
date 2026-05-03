import { zodResolver } from '@hookform/resolvers/zod';
import {
  Badge, Button, Checkbox, Form, FormControl, FormField, FormItem,
  FormLabel, FormMessage, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';

interface TreatyDto { id: string; name: string; type: string; status: string; }

interface AllocationRow {
  id:             string;
  policyNumber:   string;
  classOfBusiness:string;
  sumInsured:     number;
  treatyName:     string;
  status:         string;
}

const schema = z.object({
  selectedIds:    z.array(z.string()).min(1, 'Select at least one policy'),
  newTreatyId:    z.string().min(1, 'Select the new treaty'),
  reason:         z.string().min(5, 'Provide a reason for reallocation'),
  effectiveDate:  z.string().min(1, 'Required'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  allocations:   AllocationRow[];
  onSuccess:     () => void;
}

export default function BatchReallocationSheet({ open, onOpenChange, allocations, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const treatiesQuery = useQuery<TreatyDto[]>({
    queryKey: ['reinsurance', 'treaties', { status: 'ACTIVE' }],
    queryFn: async () => {
      const res = await apiClient.get<{ data: TreatyDto[] }>('/api/v1/reinsurance/treaties', {
        params: { status: 'ACTIVE' },
      });
      return res.data.data;
    },
    enabled: open,
  });
  const activeTreaties = treatiesQuery.data ?? [];

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema),
    defaultValues: { selectedIds: [], newTreatyId: '', reason: '', effectiveDate: '' },
  });

  const selectedIds = form.watch('selectedIds');
  const reallocatable = allocations.filter(a => a.status !== 'APPROVED');

  function toggleSelection(id: string, currentIds: string[], onChange: (v: string[]) => void) {
    if (currentIds.includes(id)) {
      onChange(currentIds.filter(i => i !== id));
    } else {
      onChange([...currentIds, id]);
    }
  }

  function selectAll(onChange: (v: string[]) => void) {
    onChange(reallocatable.map(a => a.id));
  }

  const reallocate = useMutation({
    mutationFn: async (values: FormValues) => {
      const res = await apiClient.post<{ data: { reallocatedCount: number } }>(
        '/api/v1/reinsurance/allocations/batch-reallocate', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reinsurance', 'allocations'] });
      onSuccess();
      form.reset();
    },
  });

  function onSubmit(values: FormValues) {
    reallocate.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Batch Reallocation</SheetTitle>
          <SheetDescription>
            Select the policies to reallocate and choose the new treaty. Use this when treaty parameters change mid-year.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            {/* Policy selection */}
            <FormField control={form.control} name="selectedIds"
              render={({ field }) => (
                <FormItem>
                  <div className="flex items-center justify-between mb-2">
                    <FormLabel>Select Policies to Reallocate</FormLabel>
                    <Button
                      type="button" variant="ghost" size="sm"
                      className="h-7 text-xs text-primary"
                      onClick={() => selectAll(field.onChange)}
                    >
                      Select all ({reallocatable.length})
                    </Button>
                  </div>
                  <div className="space-y-2 rounded-lg border p-3 max-h-56 overflow-y-auto">
                    {reallocatable.map(a => (
                      <div
                        key={a.id}
                        className="flex items-start gap-3 rounded-md p-2 hover:bg-muted/40 cursor-pointer"
                        onClick={() => toggleSelection(a.id, field.value, field.onChange)}
                      >
                        <Checkbox
                          checked={field.value.includes(a.id)}
                          onCheckedChange={() => toggleSelection(a.id, field.value, field.onChange)}
                          className="mt-0.5"
                        />
                        <div className="flex-1 min-w-0">
                          <p className="font-mono text-xs text-primary">{a.policyNumber}</p>
                          <p className="text-xs text-muted-foreground">{a.classOfBusiness} · ₦{a.sumInsured.toLocaleString()}</p>
                          <p className="text-xs text-muted-foreground">Current: {a.treatyName}</p>
                        </div>
                        <Badge variant="draft" className="text-[10px] shrink-0">{a.status.toLowerCase().replace('_', ' ')}</Badge>
                      </div>
                    ))}
                  </div>
                  {field.value.length > 0 && (
                    <p className="text-xs text-primary mt-1">{field.value.length} polic{field.value.length === 1 ? 'y' : 'ies'} selected</p>
                  )}
                  <FormMessage />
                </FormItem>
              )}
            />

            <Separator />

            {/* New treaty */}
            <FormField control={form.control} name="newTreatyId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>New Treaty</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select the treaty to reallocate to" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {activeTreaties.map(t => (
                        <SelectItem key={t.id} value={t.id}>
                          {t.name} <span className="text-muted-foreground ml-1">({t.type})</span>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="effectiveDate"
              render={({ field }) => (<FormItem><FormLabel>Effective Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormField control={form.control} name="reason"
              render={({ field }) => (<FormItem><FormLabel>Reason for Reallocation</FormLabel><FormControl><Input placeholder="e.g. Treaty parameters changed — retention limit increased" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={reallocate.isPending || selectedIds.length === 0}>
                {reallocate.isPending ? 'Processing…' : `Reallocate ${selectedIds.length > 0 ? selectedIds.length : ''} Polic${selectedIds.length === 1 ? 'y' : 'ies'}`}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
