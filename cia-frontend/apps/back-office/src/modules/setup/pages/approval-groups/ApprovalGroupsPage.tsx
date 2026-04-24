import { useState } from 'react';
import {
  Badge, Button, Card, CardContent, DataTableRowActions, EmptyState,
  PageHeader, Separator,
} from '@cia/ui';
import type { ApprovalGroupDto } from '@cia/api-client';
import { type Row } from '@tanstack/react-table';
import ApprovalGroupSheet from './ApprovalGroupSheet';

const mockGroups: ApprovalGroupDto[] = [
  {
    id: '1', name: 'Policy Approval', module: 'UNDERWRITING',
    levels: [
      { level: 1, minAmount: 0,         maxAmount: 10_000_000, approverIds: ['u2'], approverNames: ['Chidi Okafor'] },
      { level: 2, minAmount: 10_000_000, maxAmount: 50_000_000, approverIds: ['u1'], approverNames: ['Akinwale Nubeero'] },
    ],
  },
  {
    id: '2', name: 'Claims Approval', module: 'CLAIMS',
    levels: [
      { level: 1, minAmount: 0, maxAmount: 5_000_000, approverIds: ['u3'], approverNames: ['Adaeze Nwosu'] },
    ],
  },
];

const MODULE_LABELS: Record<string, string> = {
  UNDERWRITING: 'Underwriting', CLAIMS: 'Claims', FINANCE: 'Finance',
  ENDORSEMENT: 'Endorsements', QUOTATION: 'Quotation',
};

export default function ApprovalGroupsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ApprovalGroupDto | null>(null);

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(g: ApprovalGroupDto) { setEditing(g); setSheetOpen(true); }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Approval Groups"
        description="Configure multi-level approval hierarchies for policies, claims, and finance transactions."
        actions={<Button onClick={openCreate}>Add Group</Button>}
      />

      {mockGroups.length === 0 ? (
        <EmptyState
          title="No approval groups yet"
          description="Create approval groups to enforce authorisation thresholds."
          action={<Button onClick={openCreate}>Add Group</Button>}
        />
      ) : (
        <div className="space-y-3">
          {mockGroups.map((group) => (
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
