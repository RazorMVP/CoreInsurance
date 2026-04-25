import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Badge, Breadcrumb, Card, CardContent, Skeleton } from '@cia/ui';
import { useReportDefinition } from '../../hooks/useReportDefinitions';
import { useRunReport } from '../../hooks/useRunReport';
import { useReportPins } from '../../hooks/useReportPins';
import { CATEGORY_COLORS, CATEGORY_LABELS } from '../../types/report.types';
import type { ReportRunRequest } from '../../types/report.types';
import ReportFilterForm from './ReportFilterForm';
import ReportResultTable from './ReportResultTable';
import ReportChart from './ReportChart';
import ReportExportBar from './ReportExportBar';

export default function ReportViewerPage() {
  const { id } = useParams<{ id: string }>();
  const { data: report, isLoading } = useReportDefinition(id);
  const { data: pins = [] } = useReportPins();
  const runReport = useRunReport();
  const [lastRequest, setLastRequest] = useState<ReportRunRequest | null>(null);

  const isPinned = pins.some((p) => p.id === id);

  function handleRun(filters: Record<string, string>) {
    if (!id) return;
    const req: ReportRunRequest = { reportId: id, filters };
    setLastRequest(req);
    runReport.mutate(req);
  }

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-4 w-96" />
        <Skeleton className="h-40 w-full rounded-lg" />
      </div>
    );
  }

  if (!report) return null;

  const filters = report.config.filters ?? [];
  const result = runReport.data;

  return (
    <div className="p-6 space-y-5">
      {/* Breadcrumb */}
      <Breadcrumb
        items={[
          { label: 'Reports', href: '/reports' },
          { label: CATEGORY_LABELS[report.category], href: '/reports/library' },
          { label: report.name },
        ]}
      />

      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <h1 className="text-2xl font-semibold">{report.name}</h1>
            <Badge className={`text-xs border ${CATEGORY_COLORS[report.category]}`}>
              {CATEGORY_LABELS[report.category]}
            </Badge>
            {report.type === 'SYSTEM' && (
              <Badge variant="outline" className="text-xs">Pre-built</Badge>
            )}
          </div>
          {report.description && (
            <p className="text-sm text-muted-foreground">{report.description}</p>
          )}
        </div>
      </div>

      {/* Filters */}
      <Card>
        <CardContent className="pt-5">
          <p className="text-sm font-medium mb-4">Filters</p>
          <ReportFilterForm
            filters={filters}
            onRun={handleRun}
            loading={runReport.isPending}
          />
        </CardContent>
      </Card>

      {/* Results */}
      {result && lastRequest && (
        <Card>
          <CardContent className="pt-5 space-y-4">
            {report.config.chart && report.config.chart.type !== 'TABLE_ONLY' && (
              <ReportChart chart={report.config.chart} data={result.rows as Record<string, unknown>[]} />
            )}
            <ReportResultTable
              columns={result.columns}
              rows={result.rows as Record<string, unknown>[]}
              totalRows={result.totalRows}
            />
            <ReportExportBar report={report} request={lastRequest} isPinned={isPinned} />
          </CardContent>
        </Card>
      )}

      {runReport.isError && (
        <p className="text-sm text-destructive">
          Failed to run report. Please check your filters and try again.
        </p>
      )}
    </div>
  );
}
