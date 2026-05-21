import { useEffect, useState } from 'react';
import {
  Button, Input, Label, Skeleton,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  toast,
} from '@cia/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type ApiError, type ApiResponse,
  type InsuranceCompanyDto,
  type PolicyCoinsuranceParticipantDto,
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

interface DraftRow {
  // empty insuranceCompanyId means "freshly added, not yet selected"
  insuranceCompanyId:   string;
  insuranceCompanyName: string;
  sharePercentage:      string; // string for input control; coerced on submit
}

interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  policyId:      string;
  policyNumber:  string;
  participants:  PolicyCoinsuranceParticipantDto[];
  onSuccess:     () => void;
}

export default function CoinsuranceEditorDialog({
  open, onOpenChange, policyId, policyNumber, participants, onSuccess,
}: Props) {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState<DraftRow[]>([]);

  // Reset draft when the dialog opens or the underlying participants change.
  useEffect(() => {
    if (!open) return;
    setRows(participants.map(p => ({
      insuranceCompanyId:   p.insuranceCompanyId,
      insuranceCompanyName: p.insuranceCompanyName,
      sharePercentage:      p.sharePercentage.toString(),
    })));
  }, [open, participants]);

  const companiesQuery = useQuery<InsuranceCompanyDto[]>({
    queryKey: ['setup', 'insurance-companies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: { content: InsuranceCompanyDto[] } }>(
        '/api/v1/setup/insurance-companies',
        { params: { size: 200 } },
      );
      return res.data.data ?? [];
    },
    enabled: open,
  });
  const companies = companiesQuery.data ?? [];

  const totalShare = rows.reduce((s, r) => s + (Number(r.sharePercentage) || 0), 0);
  const isValid =
    rows.length > 0
    && rows.every(r => r.insuranceCompanyId && Number(r.sharePercentage) > 0 && Number(r.sharePercentage) <= 99.99)
    && Math.abs(totalShare - 100) < 0.01;

  function addRow() {
    setRows([...rows, { insuranceCompanyId: '', insuranceCompanyName: '', sharePercentage: '' }]);
  }
  function updateRow(idx: number, patch: Partial<DraftRow>) {
    setRows(rows.map((r, i) => i === idx ? { ...r, ...patch } : r));
  }
  function removeRow(idx: number) {
    setRows(rows.filter((_, i) => i !== idx));
  }

  const save = useMutation({
    mutationFn: async () => {
      const payload = rows.map(r => ({
        insuranceCompanyId: r.insuranceCompanyId,
        sharePercentage:    Number(r.sharePercentage),
      }));
      const res = await apiClient.put<{ data: unknown }>(
        `/api/v1/policies/${policyId}/coinsurance`,
        payload,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies', policyId] });
      toast({ title: 'Coinsurance shares updated' });
      onSuccess();
    },
    onError: (e) => showServerError(e, 'Could not update coinsurance'),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>Coinsurance Shares</SheetTitle>
          <SheetDescription>
            Manage the participating insurers and their share % for <span className="font-medium text-foreground">{policyNumber}</span>.
            Shares must sum to exactly 100%.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-3">
          {companiesQuery.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : (
            <>
              {rows.length === 0 && (
                <p className="text-sm text-muted-foreground py-6 text-center">
                  No participants yet. Add at least one participating insurer.
                </p>
              )}
              {rows.map((r, idx) => (
                <div key={idx} className="grid grid-cols-[1fr_140px_auto] gap-2 items-end">
                  <div className="space-y-1.5">
                    {idx === 0 && <Label className="text-xs">Insurer</Label>}
                    <Select
                      value={r.insuranceCompanyId}
                      onValueChange={(v) => {
                        const co = companies.find(c => c.id === v);
                        updateRow(idx, {
                          insuranceCompanyId:   v,
                          insuranceCompanyName: co?.name ?? '',
                        });
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Select insurer" />
                      </SelectTrigger>
                      <SelectContent>
                        {companies.map(c => (
                          <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    {idx === 0 && <Label className="text-xs">Share %</Label>}
                    <Input
                      type="number" step={0.01} min={0.01} max={99.99}
                      value={r.sharePercentage}
                      onChange={(e) => updateRow(idx, { sharePercentage: e.target.value })}
                    />
                  </div>
                  <Button variant="outline" size="sm" onClick={() => removeRow(idx)}>
                    Remove
                  </Button>
                </div>
              ))}
              <Button variant="outline" size="sm" onClick={addRow}>
                + Add Participant
              </Button>
            </>
          )}
          <div className="rounded-lg border bg-muted/40 px-4 py-3 mt-4 flex items-center justify-between">
            <p className="text-sm font-medium text-foreground">Total Share</p>
            <p className={`text-lg font-semibold tabular-nums ${Math.abs(totalShare - 100) < 0.01 ? 'text-primary' : 'text-destructive'}`}>
              {totalShare.toFixed(2)}%
            </p>
          </div>
          {!isValid && rows.length > 0 && Math.abs(totalShare - 100) >= 0.01 && (
            <p className="text-xs text-destructive">
              Shares must sum to 100% ({(100 - totalShare).toFixed(2)}% remaining).
            </p>
          )}
        </div>

        <SheetFooter className="mt-6">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>
            Cancel
          </Button>
          <Button disabled={!isValid || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save Shares'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
