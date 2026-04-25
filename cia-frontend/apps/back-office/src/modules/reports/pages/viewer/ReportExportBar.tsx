import { Button } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { Bookmark01Icon, BookmarkRemove01Icon, Download01Icon } from '@hugeicons/core-free-icons';
import type { ReportDefinition, ReportRunRequest } from '../../types/report.types';
import { useExportCsv, useExportPdf } from '../../hooks/useRunReport';
import { usePinReport, useUnpinReport } from '../../hooks/useReportPins';

interface Props {
  report: ReportDefinition;
  request: ReportRunRequest;
  isPinned: boolean;
}

export default function ReportExportBar({ report, request, isPinned }: Props) {
  const exportCsv = useExportCsv();
  const exportPdf = useExportPdf();
  const pin = usePinReport();
  const unpin = useUnpinReport();

  return (
    <div className="flex items-center gap-2 border-t pt-3 mt-1">
      <Button
        variant="outline"
        size="sm"
        onClick={() => exportCsv.mutate(request)}
        disabled={exportCsv.isPending}
      >
        <HugeiconsIcon icon={Download01Icon} size={14} color="currentColor" strokeWidth={1.75} className="mr-1.5" />
        Export CSV
      </Button>

      <Button
        variant="outline"
        size="sm"
        onClick={() => exportPdf.mutate(request)}
        disabled={exportPdf.isPending}
      >
        <HugeiconsIcon icon={Download01Icon} size={14} color="currentColor" strokeWidth={1.75} className="mr-1.5" />
        Export PDF
      </Button>

      {report.pinnable && (
        <Button
          variant="ghost"
          size="sm"
          className="ml-auto"
          onClick={() => isPinned ? unpin.mutate(report.id) : pin.mutate(report.id)}
          disabled={pin.isPending || unpin.isPending}
        >
          <HugeiconsIcon
            icon={isPinned ? BookmarkRemove01Icon : Bookmark01Icon}
            size={14}
            color="currentColor"
            strokeWidth={1.75}
            className="mr-1.5"
          />
          {isPinned ? 'Unpin' : 'Pin to Home'}
        </Button>
      )}
    </div>
  );
}
