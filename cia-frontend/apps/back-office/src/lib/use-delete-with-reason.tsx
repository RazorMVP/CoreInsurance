import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ConfirmDeleteDialog } from '@cia/ui';
import { apiClient } from '@cia/api-client';

/**
 * Reusable hook for the back-office's standard "delete with reason" workflow.
 * Pairs with the backend's `DELETE /api/v1/{module}/{id}?reason=...` contract
 * and audit_log.reason (V47). The deletion is soft on the backend (deleted_at
 * is set; the row remains in the database and is extractable by auditors).
 *
 * Usage:
 *   const { setTarget, dialog } = useDeleteWithReason<BrokerDto>({
 *     endpoint: (id) => `/api/v1/setup/brokers/${id}`,
 *     invalidateKey: ['setup', 'brokers'],
 *     entityLabel: 'Broker',
 *     entityName: (b) => b.name,
 *   });
 *   // In a row action: onClick: (r) => setTarget(r.original)
 *   // In the JSX:      {dialog}
 */
export function useDeleteWithReason<T extends { id: string }>(opts: {
  endpoint: (id: string) => string;
  invalidateKey: readonly unknown[];
  entityLabel: string;
  entityName: (t: T) => string;
  onSuccess?: () => void;
}) {
  const [target, setTarget] = useState<T | null>(null);
  const queryClient = useQueryClient();
  const del = useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) => {
      await apiClient.delete(`${opts.endpoint(id)}?reason=${encodeURIComponent(reason)}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: opts.invalidateKey });
      setTarget(null);
      opts.onSuccess?.();
    },
  });
  const dialog = (
    <ConfirmDeleteDialog
      open={!!target}
      onOpenChange={(v) => { if (!v) setTarget(null); }}
      entityLabel={opts.entityLabel}
      entityName={target ? opts.entityName(target) : undefined}
      busy={del.isPending}
      onConfirm={(reason) => { if (target) del.mutate({ id: target.id, reason }); }}
    />
  );
  return { setTarget, dialog };
}
