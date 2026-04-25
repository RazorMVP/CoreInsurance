import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Badge, Button, Card, CardContent, Input, PageHeader, Skeleton, Tabs, TabsList, TabsTrigger } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { BarChartIcon, Clock01Icon, Search01Icon } from '@hugeicons/core-free-icons';
import { useReportDefinitions, useCloneReport } from '../../hooks/useReportDefinitions';
import { CATEGORY_COLORS, CATEGORY_LABELS } from '../../types/report.types';
import type { ReportCategory, ReportDefinition } from '../../types/report.types';

const ALL_CATEGORIES: Array<{ value: ReportCategory | 'ALL'; label: string }> = [
  { value: 'ALL',          label: 'All' },
  { value: 'UNDERWRITING', label: 'Underwriting' },
  { value: 'CLAIMS',       label: 'Claims' },
  { value: 'FINANCE',      label: 'Finance' },
  { value: 'REINSURANCE',  label: 'Reinsurance' },
  { value: 'CUSTOMER',     label: 'Customer' },
  { value: 'REGULATORY',   label: 'Regulatory' },
];

interface LibraryCardProps {
  report: ReportDefinition;
  onClone: (id: string) => void;
  cloning: boolean;
}

function LibraryCard({ report, onClone, cloning }: LibraryCardProps) {
  return (
    <Card className="group hover:shadow-md hover:border-primary/30 transition-all">
      <CardContent className="p-4 flex items-start gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-secondary">
          <HugeiconsIcon icon={BarChartIcon} size={16} color="currentColor" strokeWidth={1.75} />
        </div>
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex items-center gap-2 flex-wrap">
            <Link
              to={`/reports/run/${report.id}`}
              className="text-sm font-medium group-hover:text-primary transition-colors"
            >
              {report.name}
            </Link>
            <Badge className={`text-[10px] border ${CATEGORY_COLORS[report.category]}`}>
              {CATEGORY_LABELS[report.category]}
            </Badge>
            {report.type === 'SYSTEM' && (
              <Badge variant="outline" className="text-[10px]">Pre-built</Badge>
            )}
          </div>
          {report.description && (
            <p className="text-xs text-muted-foreground line-clamp-2">{report.description}</p>
          )}
          <div className="flex items-center gap-2 pt-1">
            <Link to={`/reports/run/${report.id}`}>
              <Button size="sm" variant="ghost" className="h-7 px-2 text-xs">
                Run Report →
              </Button>
            </Link>
            {report.type === 'SYSTEM' && (
              <Button
                size="sm"
                variant="ghost"
                className="h-7 px-2 text-xs text-muted-foreground"
                onClick={() => onClone(report.id)}
                disabled={cloning}
              >
                {cloning ? 'Cloning…' : 'Clone & Edit'}
              </Button>
            )}
          </div>
        </div>
        <div className="shrink-0 text-xs text-muted-foreground flex items-center gap-1">
          <HugeiconsIcon icon={Clock01Icon} size={11} color="currentColor" strokeWidth={1.75} />
          —
        </div>
      </CardContent>
    </Card>
  );
}

export default function ReportLibraryPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const initialCategory = (searchParams.get('category') ?? 'ALL') as ReportCategory | 'ALL';
  const [activeCategory, setActiveCategory] = useState<ReportCategory | 'ALL'>(initialCategory);
  const [search, setSearch] = useState('');
  const [cloningId, setCloningId] = useState<string | null>(null);

  const { data: reports = [], isLoading } = useReportDefinitions(
    activeCategory === 'ALL' ? undefined : activeCategory
  );
  const cloneReport = useCloneReport();

  const filtered = search.trim()
    ? reports.filter(
        (r) =>
          r.name.toLowerCase().includes(search.toLowerCase()) ||
          r.description?.toLowerCase().includes(search.toLowerCase())
      )
    : reports;

  function handleClone(id: string) {
    setCloningId(id);
    cloneReport.mutate(
      { id },
      {
        onSuccess: (cloned) => {
          setCloningId(null);
          navigate(`/reports/custom/${cloned.id}`);
        },
        onError: () => setCloningId(null),
      }
    );
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Report Library"
        description={`${reports.length} reports available`}
        actions={
          <Link to="/reports/custom">
            <Button size="sm">+ New Custom Report</Button>
          </Link>
        }
      />

      <div className="relative max-w-sm">
        <HugeiconsIcon
          icon={Search01Icon}
          size={15}
          color="currentColor"
          strokeWidth={1.75}
          className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          className="pl-9"
          placeholder="Search reports…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <Tabs value={activeCategory} onValueChange={(v) => setActiveCategory(v as ReportCategory | 'ALL')}>
        <TabsList className="flex-wrap h-auto gap-1">
          {ALL_CATEGORIES.map((cat) => (
            <TabsTrigger key={cat.value} value={cat.value} className="text-xs">
              {cat.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {isLoading ? (
        <div className="space-y-3">
          {[0,1,2,3,4].map(i => <Skeleton key={i} className="h-24 rounded-lg" />)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground text-sm">
          No reports match your search.
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((r) => (
            <LibraryCard
              key={r.id}
              report={r}
              onClone={handleClone}
              cloning={cloningId === r.id}
            />
          ))}
        </div>
      )}
    </div>
  );
}
