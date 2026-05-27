import { useEffect, useRef, useState } from 'react';
import {
  Badge, Button,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { useEmailReceipt, useCancelReceiptEmail } from '../hooks/useReceipts';
import { useEmailPayment, useCancelPaymentEmail } from '../hooks/usePayments';
import type { PdfDocumentType } from '@cia/api-client';

interface BulkRow {
  id:        string;
  parentId:  string;       // dnId for RECEIPT, cnId for PAYMENT
  reference: string;
}

type RowStatus = 'queued' | 'sending' | 'sent' | 'failed' | 'cancelled';

interface Props {
  type:         PdfDocumentType;
  rows:         BulkRow[];
  open:         boolean;
  onOpenChange: (v: boolean) => void;
}

/**
 * Serial bulk-email runner. Sends N emails one at a time. Cancel button
 * fires the cancel-workflow mutation against the currently-sending row
 * and marks all remaining queued rows as cancelled (no further mutations
 * fire).
 */
export default function BulkEmailSheet({ type, rows, open, onOpenChange }: Props) {
  const [statuses, setStatuses] = useState<Record<string, RowStatus>>({});
  const [running,  setRunning]  = useState(false);
  const cancelRef = useRef(false);

  const emailReceipt    = useEmailReceipt();
  const emailPayment    = useEmailPayment();
  const cancelReceipt   = useCancelReceiptEmail();
  const cancelPayment   = useCancelPaymentEmail();

  useEffect(() => {
    if (open) {
      const initial: Record<string, RowStatus> = {};
      for (const r of rows) initial[r.id] = 'queued';
      setStatuses(initial);
      setRunning(false);
      cancelRef.current = false;
    }
  }, [open, rows]);

  async function runAll() {
    setRunning(true);
    for (const row of rows) {
      if (cancelRef.current) {
        setStatuses((s) => ({ ...s, [row.id]: 'cancelled' }));
        continue;
      }
      setStatuses((s) => ({ ...s, [row.id]: 'sending' }));
      try {
        if (type === 'RECEIPT') {
          await emailReceipt.mutateAsync({ dnId: row.parentId, receiptId: row.id, reference: row.reference });
        } else {
          await emailPayment.mutateAsync({ cnId: row.parentId, paymentId: row.id, reference: row.reference });
        }
        setStatuses((s) => ({ ...s, [row.id]: 'sent' }));
      } catch {
        setStatuses((s) => ({ ...s, [row.id]: 'failed' }));
      }
    }
    setRunning(false);
  }

  function onCancel() {
    cancelRef.current = true;
    // Signal the currently-sending row so the workflow aborts before its
    // next retry attempt. Best-effort — see workflow Javadoc.
    const inflight = Object.entries(statuses).find(([, s]) => s === 'sending')?.[0];
    if (inflight) {
      const row = rows.find(r => r.id === inflight);
      if (row) {
        if (type === 'RECEIPT') cancelReceipt.mutate({ dnId: row.parentId, receiptId: row.id, reference: row.reference });
        else                     cancelPayment.mutate({ cnId: row.parentId, paymentId: row.id, reference: row.reference });
      }
    }
  }

  const counts = Object.values(statuses).reduce<Record<RowStatus, number>>((acc, s) => {
    acc[s] = (acc[s] ?? 0) + 1;
    return acc;
  }, { queued: 0, sending: 0, sent: 0, failed: 0, cancelled: 0 });

  const done = !running && counts.queued === 0 && counts.sending === 0 && Object.keys(statuses).length > 0;

  return (
    <Sheet open={open} onOpenChange={(v) => { if (!running) onOpenChange(v); }}>
      <SheetContent side="right" className="w-[480px] sm:max-w-[480px]">
        <SheetHeader>
          <SheetTitle>Email {rows.length} {type === 'RECEIPT' ? 'receipts' : 'payment vouchers'}</SheetTitle>
          <SheetDescription>
            Delivery is best-effort. Cancel stops the queue — in-flight emails may still send.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-1 max-h-[60vh] overflow-y-auto">
          {rows.map((r) => {
            const s = statuses[r.id] ?? 'queued';
            return (
              <div key={r.id} className="flex items-center justify-between rounded border p-2">
                <span className="font-mono text-xs">{r.reference}</span>
                <Badge variant={badgeVariant(s)} className="text-[10px]">{s}</Badge>
              </div>
            );
          })}
        </div>

        <SheetFooter className="mt-4 flex justify-between">
          <div className="text-xs text-muted-foreground">
            sent: {counts.sent} · failed: {counts.failed} · cancelled: {counts.cancelled}
          </div>
          <div className="flex gap-2">
            {!running && !done && (
              <Button onClick={runAll}>Send all</Button>
            )}
            {running && (
              <Button variant="outline" onClick={onCancel}>Cancel remaining</Button>
            )}
            {done && (
              <Button onClick={() => onOpenChange(false)}>Close</Button>
            )}
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}

function badgeVariant(s: RowStatus): 'outline' | 'active' | 'rejected' | 'draft' {
  switch (s) {
    case 'sent':      return 'active';
    case 'failed':    return 'rejected';
    case 'cancelled': return 'draft';
    case 'sending':   return 'outline';
    default:          return 'outline';
  }
}
