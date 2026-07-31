import { useState } from 'react';
import {
  Badge, Button, Card, CardContent, DataTableRowActions, EmptyState,
  PageHeader, Separator, Skeleton,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { validatedGet, ApprovalGroupDtoSchema, type ApprovalGroupDto } from '@cia/api-client';
import { type Row } from '@tanstack/react-table';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import ApprovalGroupSheet from './ApprovalGroupSheet';

// Display labels for the backend entityType values. Vocabulary matches what
// ApprovalGroupSheet exposes via its entity-type select.
const ENTITY_TYPE_LABELS: Record<string, string> = {
  POLICY:           'Policy',
  CLAIM:            'Claim',
  ENDORSEMENT:      'Endorsement',
  QUOTE:            'Quote',
  FINANCE_RECEIPT:  'Finance — Receipt',
  FINANCE_PAYMENT:  'Finance — Payment',
};

export default function ApprovalGroupsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ApprovalGroupDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<ApprovalGroupDto>({
    endpoint: (id) => `/api/v1/setup/approval-groups/${id}`,
    invalidateKey: ['setup', 'approval-groups'],
    entityLabel: 'Approval Group',
    entityName: (g) => g.name,
  });

  const groupsQuery = useQuery<ApprovalGroupDto[]>({
    queryKey: ['setup', 'approval-groups'],
    queryFn: () => validatedGet('/api/v1/setup/approval-groups', z.array(ApprovalGroupDtoSchema)),
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
                    <Badge variant="default" className="text-[10px]">{ENTITY_TYPE_LABELS[group.entityType] ?? group.entityType}</Badge>
                  </div>
                  <DataTableRowActions
                    row={{ original: group } as Row<ApprovalGroupDto>}
                    actions={[
                      { label: 'Edit',   onClick: (r) => openEdit(r.original) },
                      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
                    ]}
                  />
                </div>

                <Separator className="my-3" />

                <div className="space-y-2">
                  <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Approval Levels</p>
                  {group.levels.map((lvl) => (
                    <div key={lvl.id} className="flex items-center justify-between rounded-md bg-muted/40 px-3 py-2">
                      <div>
                        <p className="text-xs font-medium text-foreground">Level {lvl.levelOrder}</p>
                        <p className="text-xs text-muted-foreground">{lvl.approverName}</p>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        up to {lvl.maxAmount >= 1e12 ? '∞' : `₦${lvl.maxAmount.toLocaleString()}`}
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
      {deleteDialog}
    </div>
  );
}
