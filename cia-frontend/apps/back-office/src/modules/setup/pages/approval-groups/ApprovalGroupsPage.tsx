import { useState } from 'react';
import {
  Badge, Button, Card, CardContent, DataTableRowActions, EmptyState,
  PageHeader, Separator, Skeleton,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type ApprovalGroupDto } from '@cia/api-client';
import { type Row } from '@tanstack/react-table';
import ApprovalGroupSheet from './ApprovalGroupSheet';

const MODULE_LABELS: Record<string, string> = {
  UNDERWRITING: 'Underwriting', CLAIMS: 'Claims', FINANCE: 'Finance',
  ENDORSEMENT: 'Endorsements', QUOTATION: 'Quotation',
};

export default function ApprovalGroupsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ApprovalGroupDto | null>(null);

  const groupsQuery = useQuery<ApprovalGroupDto[]>({
    queryKey: ['setup', 'approval-groups'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ApprovalGroupDto[] }>('/api/v1/setup/approval-groups');
      return res.data.data;
    },
  });
  const groups = groupsQuery.data ?? [];

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(g: ApprovalGroupDto) { setEditing(g); setSheetOpen(true); }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Approval Groups"
        description="Configure multi-level approval hierarchies for policies, claims, and finance transactions."
        actions={<Button onClick={openCreate}>Add Group</Button>}
      />

      {groupsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-32 w-full rounded-lg" /><Skeleton className="h-32 w-full rounded-lg" /></div>
      ) : groups.length === 0 ? (
        <EmptyState
          title="No approval groups yet"
          description="Create approval groups to enforce authorisation thresholds."
          action={<Button onClick={openCreate}>Add Group</Button>}
        />
      ) : (
        <div className="space-y-3">
          {groups.map((group) => (
            <Card key={group.id}>
              <CardContent className="p-5">
                <div className="flex items-start justify-between">
                  <div className="space-y-0.5">
                    <p className="font-display text-sm font-semibold text-foreground">{group.name}</p>
                    <Badge variant="default" className="text-[10px]">{MODULE_LABELS[group.module] ?? group.module}</Badge>
                  </div>
                  <DataTableRowActions
                    row={{ original: group } as Row<ApprovalGroupDto>}
                    actions={[
                      { label: 'Edit',   onClick: (r) => openEdit(r.original) },
                      { label: 'Delete', onClick: () => {}, separator: true, className: 'text-destructive' },
                    ]}
                  />
                </div>

                <Separator className="my-3" />

                <div className="space-y-2">
                  <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Approval Levels</p>
                  {group.levels.map((lvl) => (
                    <div key={lvl.level} className="flex items-center justify-between rounded-md bg-muted/40 px-3 py-2">
                      <div>
                        <p className="text-xs font-medium text-foreground">Level {lvl.level}</p>
                        <p className="text-xs text-muted-foreground">{lvl.approverNames.join(', ')}</p>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        ₦{lvl.minAmount.toLocaleString()} – {lvl.maxAmount >= 1e12 ? '∞' : `₦${lvl.maxAmount.toLocaleString()}`}
                      </p>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <ApprovalGroupSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        group={editing}
        onSuccess={() => setSheetOpen(false)}
      />
    </div>
  );
}
