import { useEffect, useState } from 'react';
import {
  Button, Input, Label,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  toast,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type ApiError, type ApiResponse,
  type PolicyRiskDto,
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
  id?:               string;          // present on existing rows; undefined for new
  description:       string;
  sumInsured:        string;
  vehicleRegNumber:  string;
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  policyId:     string;
  policyNumber: string;
  risks:        PolicyRiskDto[];
  /** Whether the policy class is motor — gates the vehicle reg input. */
  isMotor:      boolean;
  onSuccess:    () => void;
}

export default function RisksEditorDialog({
  open, onOpenChange, policyId, policyNumber, risks, isMotor, onSuccess,
}: Props) {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState<DraftRow[]>([]);

  useEffect(() => {
    if (!open) return;
    setRows(risks.map(r => ({
      id:               r.id,
      description:      r.description,
      sumInsured:       r.sumInsured.toString(),
      vehicleRegNumber: r.vehicleRegNumber ?? '',
    })));
  }, [open, risks]);

  const totalSi = rows.reduce((s, r) => s + (Number(r.sumInsured) || 0), 0);
  const isValid =
    rows.length > 0
    && rows.every(r => r.description.trim().length > 0 && Number(r.sumInsured) > 0);

  function addRow() {
    setRows([...rows, { description: '', sumInsured: '', vehicleRegNumber: '' }]);
  }
  function updateRow(idx: number, patch: Partial<DraftRow>) {
    setRows(rows.map((r, i) => i === idx ? { ...r, ...patch } : r));
  }
  function removeRow(idx: number) {
    setRows(rows.filter((_, i) => i !== idx));
  }

  // Bulk reconcile order matters: PUT changes first, POST new rows next,
  // DELETE removed rows last. The backend rejects deleting the last active
  // risk on a policy, so wholesale replacement (drop all old + add all new)
  // would fail if DELETE ran before POST.
  const save = useMutation({
    mutationFn: async () => {
      // 1. PUT existing edited rows
      for (const r of rows) {
        if (!r.id) continue;
        const original = risks.find(o => o.id === r.id);
        if (!original) continue;
        const changed =
          original.description !== r.description.trim()
          || original.sumInsured !== Number(r.sumInsured)
          || (original.vehicleRegNumber ?? '') !== r.vehicleRegNumber.trim();
        if (!changed) continue;
        await apiClient.put<{ data: unknown }>(
          `/api/v1/policies/${policyId}/risks/${r.id}`,
          {
            description:      r.description.trim(),
            sumInsured:       Number(r.sumInsured),
            vehicleRegNumber: r.vehicleRegNumber.trim() || null,
          },
        );
      }
      // 2. POST any new rows in one bulk call
      const newRows = rows.filter(r => !r.id);
      if (newRows.length > 0) {
        await apiClient.post<{ data: unknown }>(
          `/api/v1/policies/${policyId}/risks/bulk`,
          newRows.map(r => ({
            description:      r.description.trim(),
            sumInsured:       Number(r.sumInsured),
            vehicleRegNumber: r.vehicleRegNumber.trim() || null,
          })),
        );
      }
      // 3. DELETE rows that were dropped from the editor
      const keptIds = new Set(rows.map(r => r.id).filter((x): x is string => !!x));
      const removed = risks.filter(o => !keptIds.has(o.id));
      for (const r of removed) {
        await apiClient.delete<{ data: unknown }>(
          `/api/v1/policies/${policyId}/risks/${r.id}`,
        );
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies', policyId] });
      toast({ title: 'Risks updated' });
      onSuccess();
    },
    onError: (e) => showServerError(e, 'Could not update risks'),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>Risk Details</SheetTitle>
          <SheetDescription>
            Edit the per-item risk schedule for <span className="font-medium text-foreground">{policyNumber}</span>.
            Premium is recalculated server-side from the updated sum insured.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-3">
          {rows.length === 0 && (
            <p className="text-sm text-muted-foreground py-6 text-center">
              No risks recorded. Add at least one item.
            </p>
          )}
          {rows.map((r, idx) => (
            <div
              key={idx}
              className="grid gap-2 items-end"
              style={{ gridTemplateColumns: isMotor ? '1fr 140px 140px auto' : '1fr 160px auto' }}
            >
              <div className="space-y-1.5">
                {idx === 0 && <Label className="text-xs">Description</Label>}
                <Input
                  placeholder="e.g. 2022 Toyota Camry, Reg LND-001-AA"
                  value={r.description}
                  onChange={(e) => updateRow(idx, { description: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                {idx === 0 && <Label className="text-xs">Sum Insured (₦)</Label>}
                <Input
                  type="number" min={0.01} step={1000}
                  value={r.sumInsured}
                  onChange={(e) => updateRow(idx, { sumInsured: e.target.value })}
                />
              </div>
              {isMotor && (
                <div className="space-y-1.5">
                  {idx === 0 && <Label className="text-xs">Reg No.</Label>}
                  <Input
                    placeholder="LND-001-AA"
                    value={r.vehicleRegNumber}
                    onChange={(e) => updateRow(idx, { vehicleRegNumber: e.target.value })}
                  />
                </div>
              )}
              <Button variant="outline" size="sm" onClick={() => removeRow(idx)}>
                Remove
              </Button>
            </div>
          ))}
          <Button variant="outline" size="sm" onClick={addRow}>
            + Add Risk
          </Button>

          <div className="rounded-lg border bg-muted/40 px-4 py-3 mt-4 flex items-center justify-between">
            <p className="text-sm font-medium text-foreground">Total Sum Insured</p>
            <p className="text-lg font-semibold tabular-nums">₦{totalSi.toLocaleString()}</p>
          </div>
        </div>

        <SheetFooter className="mt-6">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>
            Cancel
          </Button>
          <Button disabled={!isValid || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save Risks'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
