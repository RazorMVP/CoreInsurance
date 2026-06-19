import { useEffect, useState } from 'react';
import {
  Button, Checkbox, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Skeleton, toast,
} from '@cia/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type ApiError, type ApiResponse,
  type ClauseDto, type ClauseSnapshotDto,
} from '@cia/api-client';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  policyId:     string;
  policyNumber: string;
  clauses:      ClauseSnapshotDto[];   // the policy's current frozen snapshot
  onSuccess:    () => void;
}

export default function ClausesEditorDialog({
  open, onOpenChange, policyId, policyNumber, clauses, onSuccess,
}: Props) {
  const queryClient = useQueryClient();
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [search, setSearch] = useState('');

  const bankQuery = useQuery<ClauseDto[]>({
    queryKey: ['setup', 'clauses'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClauseDto[] }>('/api/v1/setup/clauses');
      return res.data.data;
    },
    enabled: open,
  });

  useEffect(() => {
    if (open) setSelectedIds(clauses.map(c => c.id));
  }, [open, clauses]);

  const bank = bankQuery.data ?? [];
  const filtered = bank.filter(c =>
    search === '' || c.title.toLowerCase().includes(search.toLowerCase()) || c.text.toLowerCase().includes(search.toLowerCase()));

  function toggle(id: string) {
    setSelectedIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  }

  const save = useMutation({
    // PUT /clauses takes a raw id array (mirrors the coinsurance endpoint shape).
    mutationFn: async () => {
      await apiClient.put<{ data: unknown }>(`/api/v1/policies/${policyId}/clauses`, selectedIds);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies', policyId] });
      toast({ title: 'Clauses updated' });
      onSuccess();
    },
    onError: (e) => showServerError(e, 'Could not update clauses'),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>Applicable Clauses</SheetTitle>
          <SheetDescription>
            Select the clauses attached to <span className="font-medium text-foreground">{policyNumber}</span>.
            The clause wording is snapshotted onto the policy when you save, so later edits to the clause
            bank won't change this policy's document.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-3">
          <Input placeholder="Search clauses…" value={search} onChange={(e) => setSearch(e.target.value)} className="h-8 text-sm" />
          {bankQuery.isLoading ? (
            <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          ) : (
            <div className="rounded-md border divide-y max-h-[420px] overflow-y-auto">
              {filtered.map(c => (
                <label key={c.id} className="flex items-start gap-2.5 px-3 py-2 cursor-pointer hover:bg-secondary">
                  <Checkbox checked={selectedIds.includes(c.id)} onCheckedChange={() => toggle(c.id)} className="mt-0.5" />
                  <span className="space-y-0.5">
                    <span className="block text-sm font-medium leading-none">{c.title}</span>
                    <span className="block text-xs text-muted-foreground line-clamp-2">{c.text}</span>
                  </span>
                </label>
              ))}
              {filtered.length === 0 && (
                <p className="px-3 py-6 text-center text-sm text-muted-foreground">No clauses match.</p>
              )}
            </div>
          )}
          <p className="text-xs text-muted-foreground">{selectedIds.length} clause(s) selected.</p>
        </div>

        <SheetFooter className="mt-6">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>Cancel</Button>
          <Button disabled={save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save Clauses'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
