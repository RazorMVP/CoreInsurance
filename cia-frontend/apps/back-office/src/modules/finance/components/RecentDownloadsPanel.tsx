import { useState } from 'react';
import {
  Badge,
  Button,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
  Skeleton,
} from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { Clock01Icon, Download01Icon } from '@hugeicons/core-free-icons';
import { useRecentDownloads } from '../hooks/useRecentDownloads';
import { useDownloadReceiptPdf } from '../hooks/useReceipts';
import { useDownloadPaymentPdf } from '../hooks/usePayments';

/**
 * Right-edge Sheet showing the calling user's recent PDF downloads
 * (server-side, queryable across browsers / devices). Trigger button
 * lives in the FinancePage header.
 *
 * Re-download fires a fresh download mutation — backend logs another
 * entry, so the list grows naturally.
 */
export default function RecentDownloadsPanel() {
  const [open, setOpen] = useState(false);
  const query = useRecentDownloads(1);
  const entries = query.data?.data ?? [];
  const downloadReceipt = useDownloadReceiptPdf();
  const downloadPayment = useDownloadPaymentPdf();

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button variant="outline" size="sm">
          <HugeiconsIcon icon={Clock01Icon} size={14} />
          <span className="ml-1">Recent {entries.length > 0 ? `(${entries.length})` : ''}</span>
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="w-[420px] sm:max-w-[420px]">
        <SheetHeader>
          <SheetTitle>Recent downloads</SheetTitle>
          <SheetDescription>
            Your PDF downloads from the last 24 hours. Use this to re-pull a
            receipt or voucher you&apos;ve already sent to a customer today.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-2">
          {query.isLoading && (
            <>
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </>
          )}
          {!query.isLoading && entries.length === 0 && (
            <p className="text-sm text-muted-foreground">No downloads today yet.</p>
          )}
          {entries.map((e) => (
            <div key={e.id} className="flex items-center justify-between rounded border p-2">
              <div className="min-w-0 flex flex-col gap-0.5">
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className="text-[10px]">
                    {e.entityType === 'RECEIPT' ? 'Receipt' : 'Payment'}
                  </Badge>
                  <span className="font-mono text-xs">{e.reference}</span>
                </div>
                {e.recipientName && (
                  <span className="text-xs text-muted-foreground">{e.recipientName}</span>
                )}
                <span className="text-[11px] text-muted-foreground">
                  {new Date(e.downloadedAt).toLocaleString()}
                </span>
              </div>
              <Button
                variant="ghost"
                size="icon"
                title="Re-download"
                disabled={!e.parentId}
                onClick={() => {
                  if (!e.parentId) return;
                  if (e.entityType === 'RECEIPT') {
                    downloadReceipt.mutate({
                      dnId:      e.parentId,
                      receiptId: e.entityId,
                      reference: e.reference,
                    });
                  } else {
                    downloadPayment.mutate({
                      cnId:      e.parentId,
                      paymentId: e.entityId,
                      reference: e.reference,
                    });
                  }
                }}
              >
                <HugeiconsIcon icon={Download01Icon} size={16} />
              </Button>
            </div>
          ))}
        </div>
      </SheetContent>
    </Sheet>
  );
}
