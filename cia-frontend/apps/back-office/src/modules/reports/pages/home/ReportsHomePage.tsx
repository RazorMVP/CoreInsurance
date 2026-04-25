import { Link } from 'react-router-dom';
import { Badge, Button, Card, CardContent, EmptyState, PageHeader, Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { BarChartIcon, Bookmark01Icon, Clock01Icon, PlusSignIcon } from '@hugeicons/core-free-icons';
import { useReportDefinitions } from '../../hooks/useReportDefinitions';
import { useReportPins } from '../../hooks/useReportPins';
import { CATEGORY_COLORS, CATEGORY_LABELS } from '../../types/report.types';
import type { ReportCategory, ReportDefinition } from '../../types/report.types';

const QUICK_ACCESS_CATEGORIES: ReportCategory[] = [
  'UNDERWRITING', 'CLAIMS', 'FINANCE', 'REINSURANCE', 'CUSTOMER', 'REGULATORY',
];

function ReportCard({ report, showPin }: { report: ReportDefinition; showPin?: boolean }) {
  return (
    <Link to={`/reports/run/${report.id}`} className="group block">
      <Card className="h-full transition-all hover:shadow-md hover:border-primary/30">
        <CardContent className="p-4 space-y-2">
          <div className="flex items-start justify-between gap-2">
            <p className="text-sm font-medium leading-snug group-hover:text-primary transition-colors line-clamp-2">
              {report.name}
            </p>
            {showPin && (
              <HugeiconsIcon icon={Bookmark01Icon} size={13} color="var(--primary)" strokeWidth={2} className="shrink-0 mt-0.5" />
            )}
          </div>
          <Badge className={`text-[10px] border ${CATEGORY_COLORS[report.category]}`}>
            {CATEGORY_LABELS[report.category]}
          </Badge>
          {report.description && (
            <p className="text-xs text-muted-foreground line-clamp-2">{report.description}</p>
          )}
        </CardContent>
      </Card>
    </Link>
  );
}

function QuickAccessSection({ category }: { category: ReportCategory }) {
  const { data: reports = [], isLoading } = useReportDefinitions(category);
  const top4 = reports.slice(0, 4);

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className={`text-xs font-semibold uppercase tracking-wider px-0.5 ${CATEGORY_COLORS[category].split(' ')[0]}`}>
          {CATEGORY_LABELS[category]}
        </h3>
        <Link to={`/reports/library?category=${category}`} className="text-xs text-muted-foreground hover:text-foreground transition-colors">
          View all →
        </Link>
      </div>
      {isLoading ? (
        <div className="grid grid-cols-2 gap-2">
          {[0,1,2,3].map(i => <Skeleton key={i} className="h-20 rounded-lg" />)}
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-2">
          {top4.map(r => <ReportCard key={r.id} report={r} />)}
        </div>
      )}
    </div>
  );
}

export default function ReportsHomePage() {
  const { data: pins = [], isLoading: pinsLoading } = useReportPins();
  const recentlyRun: ReportDefinition[] = [];

  return (
    <div className="p-6 space-y-6">
      <PageHeader
        title="Reports & Analytics"
        description="55 pre-built reports across all modules. Run, export, and pin the reports you use most."
        actions={
          <Button asChild size="sm">
            <Link to="/reports/custom">
              <HugeiconsIcon icon={PlusSignIcon} size={14} color="currentColor" strokeWidth={2} className="mr-1.5" />
              New Custom Report
            </Link>
          </Button>
        }
      />

      {/* Pinned reports */}
      {(pinsLoading || pins.length > 0) && (
        <section className="space-y-3">
          <div className="flex items-center gap-2">
            <HugeiconsIcon icon={Bookmark01Icon} size={15} color="var(--primary)" strokeWidth={1.75} />
            <h2 className="text-sm font-semibold">Pinned Reports</h2>
          </div>
          {pinsLoading ? (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-3">
              {[0,1,2].map(i => <Skeleton key={i} className="h-24 rounded-lg" />)}
            </div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-3">
              {pins.map(r => <ReportCard key={r.id} report={r} showPin />)}
            </div>
          )}
        </section>
      )}

      {/* Recently run */}
      {recentlyRun.length > 0 && (
        <section className="space-y-3">
          <div className="flex items-center gap-2">
            <HugeiconsIcon icon={Clock01Icon} size={15} color="currentColor" strokeWidth={1.75} />
            <h2 className="text-sm font-semibold">Recently Run</h2>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
            {recentlyRun.map(r => <ReportCard key={r.id} report={r} />)}
          </div>
        </section>
      )}

      {/* Quick access by category */}
      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={BarChartIcon} size={15} color="currentColor" strokeWidth={1.75} />
          <h2 className="text-sm font-semibold">Quick Access</h2>
          <Link to="/reports/library" className="ml-auto text-xs text-muted-foreground hover:text-foreground transition-colors">
            View full library →
          </Link>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
          {QUICK_ACCESS_CATEGORIES.map(cat => (
            <QuickAccessSection key={cat} category={cat} />
          ))}
        </div>
      </section>

      {/* Empty pin state */}
      {!pinsLoading && pins.length === 0 && recentlyRun.length === 0 && (
        <Card className="border-dashed">
          <CardContent className="py-2">
            <EmptyState
              title="No pinned reports yet"
              description="Run any report and click 'Pin to Home' to add it here for quick access."
              action={
                <Button asChild size="sm" variant="outline">
                  <Link to="/reports/library">Browse Library</Link>
                </Button>
              }
            />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
