import { useState } from 'react';
import { Badge, Card, CardContent, CardHeader, CardTitle, Checkbox, Label, PageHeader, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type AccessGroupDto } from '@cia/api-client';
import { useReportDefinitions } from '../../hooks/useReportDefinitions';
import { useReportAccessPolicies, useUpsertAccessPolicy } from '../../hooks/useReportAccessPolicies';
import { CATEGORY_LABELS } from '../../types/report.types';
import type { ReportCategory } from '../../types/report.types';

const CATEGORIES = Object.keys(CATEGORY_LABELS) as ReportCategory[];

interface CategoryRowProps {
  category: ReportCategory;
  accessGroupId: string;
}

function CategoryRow({ category, accessGroupId }: CategoryRowProps) {
  const { data: policies = [] } = useReportAccessPolicies(accessGroupId);
  const { data: reports = [] } = useReportDefinitions(category);
  const upsert = useUpsertAccessPolicy();
  const [expanded, setExpanded] = useState(false);

  const categoryPolicy = policies.find((p) => p.category === category && !p.report);

  function toggleCategory(field: 'canView' | 'canExportCsv' | 'canExportPdf') {
    upsert.mutate({
      accessGroupId,
      category,
      canView:       field === 'canView'      ? !categoryPolicy?.canView      : (categoryPolicy?.canView ?? false),
      canExportCsv:  field === 'canExportCsv' ? !categoryPolicy?.canExportCsv : (categoryPolicy?.canExportCsv ?? false),
      canExportPdf:  field === 'canExportPdf' ? !categoryPolicy?.canExportPdf : (categoryPolicy?.canExportPdf ?? false),
    });
  }

  return (
    <div className="border rounded-lg overflow-hidden">
      <div className="flex items-center gap-4 p-3 bg-secondary/40">
        <button
          className="text-xs font-medium text-muted-foreground hover:text-foreground transition-colors mr-auto"
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? '▾' : '▸'} {CATEGORY_LABELS[category]}
          <Badge variant="default" className="ml-2 text-[10px]">{reports.length}</Badge>
        </button>
        {(['canView', 'canExportCsv', 'canExportPdf'] as const).map((field) => (
          <div key={field} className="flex items-center gap-1.5">
            <Checkbox
              id={`${category}-${field}`}
              checked={categoryPolicy?.[field] ?? false}
              onCheckedChange={() => toggleCategory(field)}
              disabled={upsert.isPending}
            />
            <Label htmlFor={`${category}-${field}`} className="text-xs cursor-pointer">
              {field === 'canView' ? 'View' : field === 'canExportCsv' ? 'CSV' : 'PDF'}
            </Label>
          </div>
        ))}
      </div>

      {expanded && reports.length > 0 && (
        <div className="divide-y">
          {reports.map((report) => {
            const reportPolicy = policies.find((p) => p.report?.id === report.id);
            return (
              <div key={report.id} className="flex items-center gap-4 p-2 pl-8">
                <span className="text-xs flex-1 text-muted-foreground">{report.name}</span>
                {(['canView', 'canExportCsv', 'canExportPdf'] as const).map((field) => (
                  <div key={field} className="flex items-center gap-1.5">
                    <Checkbox
                      id={`${report.id}-${field}`}
                      checked={reportPolicy ? reportPolicy[field] : undefined}
                      className="opacity-60"
                      onCheckedChange={() =>
                        upsert.mutate({
                          accessGroupId,
                          reportId: report.id,
                          canView:      field === 'canView'      ? !reportPolicy?.canView      : (reportPolicy?.canView      ?? categoryPolicy?.canView      ?? false),
                          canExportCsv: field === 'canExportCsv' ? !reportPolicy?.canExportCsv : (reportPolicy?.canExportCsv ?? categoryPolicy?.canExportCsv ?? false),
                          canExportPdf: field === 'canExportPdf' ? !reportPolicy?.canExportPdf : (reportPolicy?.canExportPdf ?? categoryPolicy?.canExportPdf ?? false),
                        })
                      }
                      disabled={upsert.isPending}
                    />
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function ReportAccessSetupPage() {
  const [selectedGroup, setSelectedGroup] = useState<string>('');

  const groupsQuery = useQuery<AccessGroupDto[]>({
    queryKey: ['setup', 'access-groups'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: AccessGroupDto[] }>('/api/v1/setup/access-groups');
      return res.data.data;
    },
  });
  const groups = groupsQuery.data ?? [];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Report Access Control"
        description="Grant or revoke report access per access group. Category-level permissions apply to all reports in that category; per-report settings override the category."
      />

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">Select Access Group</CardTitle>
        </CardHeader>
        <CardContent>
          <Select value={selectedGroup} onValueChange={setSelectedGroup}>
            <SelectTrigger className="max-w-xs">
              <SelectValue placeholder="Choose access group…" />
            </SelectTrigger>
            <SelectContent>
              {groups.map((g) => (
                <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </CardContent>
      </Card>

      {selectedGroup && (
        <Card>
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base">Permission Matrix</CardTitle>
              <div className="flex items-center gap-6 pr-2 text-xs text-muted-foreground">
                <span className="w-12 text-center">View</span>
                <span className="w-12 text-center">CSV</span>
                <span className="w-12 text-center">PDF</span>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            {CATEGORIES.map((cat) => (
              <CategoryRow key={cat} category={cat} accessGroupId={selectedGroup} />
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
