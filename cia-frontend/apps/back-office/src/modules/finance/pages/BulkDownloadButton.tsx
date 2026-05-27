import { Button } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { Download01Icon } from '@hugeicons/core-free-icons';
import { useBulkDownloadZip } from '../hooks/useBulkDownloadZip';
import type { BulkDownloadItem } from '@cia/api-client';

interface Props {
  items: BulkDownloadItem[];
}

/**
 * Toolbar button — visible when ≥1 row selected AND each selected row
 * has a non-null pdf_path (caller's job to filter). Click → POST to
 * /pdfs/bulk-download → single browser save.
 *
 * Disabled when items.length > 50 (backend cap; tooltip explains).
 */
export default function BulkDownloadButton({ items }: Props) {
  const mutation = useBulkDownloadZip();
  const over     = items.length > 50;
  const disabled = items.length === 0 || over || mutation.isPending;

  return (
    <Button
      variant="outline"
      size="sm"
      disabled={disabled}
      title={over
        ? `Bulk download is capped at 50 — you've selected ${items.length}`
        : items.length === 0
          ? 'Select rows to download'
          : `Download ${items.length} as ZIP`}
      onClick={() => mutation.mutate(items)}
    >
      <HugeiconsIcon icon={Download01Icon} size={14} />
      <span className="ml-1">
        {mutation.isPending ? 'Packaging…' : `Download ${items.length} as ZIP`}
      </span>
    </Button>
  );
}
