import { useMutation } from '@tanstack/react-query';
import { bulkDownloadZip, type BulkDownloadItem } from '@cia/api-client';
import { toast } from '@cia/ui';

/**
 * Triggers the backend ZIP build, then fires a browser save dialog with
 * a deterministic filename. The backend caps at 50 items; the UI should
 * gate the trigger before reaching the mutation, but if a >50 request
 * sneaks through the toast surfaces the failure.
 */
export function useBulkDownloadZip() {
  return useMutation({
    mutationFn: async (items: BulkDownloadItem[]) => {
      const blob = await bulkDownloadZip(items);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      // ISO timestamp, colons stripped (Windows filename-safe)
      const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      a.download = `cia-pdfs-${ts}.zip`;
      a.click();
      URL.revokeObjectURL(url);
      return items.length;
    },
    onSuccess: (count) => {
      toast({
        title: 'ZIP downloaded',
        description: `${count} PDF${count === 1 ? '' : 's'} packaged.`,
      });
    },
    onError: () => {
      toast({
        variant: 'destructive',
        title: 'Bulk download failed',
        description: 'Could not build the ZIP. Try again or download individually.',
      });
    },
  });
}
