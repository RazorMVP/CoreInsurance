import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import {
  validatedGet,
  BrokerDtoSchema, BranchDtoSchema, SbuDtoSchema, SurveyorDtoSchema,
  InsuranceCompanyDtoSchema, ReinsuranceCompanyDtoSchema, AdjusterDtoSchema,
  AgentDtoSchema, RelationshipManagerDtoSchema,
  type BrokerDto, type BranchDto, type SbuDto, type SurveyorDto,
  type InsuranceCompanyDto, type ReinsuranceCompanyDto, type AdjusterDto,
  type AgentDto, type RelationshipManagerDto,
} from '@cia/api-client';
import BrokerSheet from './BrokerSheet';
import BranchSheet from './BranchSheet';
import SbuSheet from './SbuSheet';
import SurveyorSheet from './SurveyorSheet';
import InsurerSheet from './InsurerSheet';
import ReinsurerSheet from './ReinsurerSheet';
import AdjusterSheet from './AdjusterSheet';
import AgentSheet from './AgentSheet';
import RelationshipManagerSheet from './RelationshipManagerSheet';

// ── Brokers ──────────────────────────────────────────────────────────────────

function BrokersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<BrokerDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<BrokerDto>({
    endpoint: (id) => `/api/v1/setup/brokers/${id}`,
    invalidateKey: ['setup', 'brokers'],
    entityLabel: 'Broker',
    entityName: (b) => b.name,
  });

  const query = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: () => validatedGet('/api/v1/setup/brokers', z.array(BrokerDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<BrokerDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Broker" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    { accessorKey: 'rcNumber',      header: 'RC Number',      cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'licenseNumber', header: 'NAICOM License', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'email',         header: 'Email',          cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',         header: 'Phone',          cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Broker</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No brokers yet" description="Add the first broker to begin tracking commissions and policy placements." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search brokers…' }} />
      )}
      <BrokerSheet open={sheetOpen} onOpenChange={setSheetOpen} broker={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Reinsurers ───────────────────────────────────────────────────────────────

function ReinsurersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ReinsuranceCompanyDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<ReinsuranceCompanyDto>({
    endpoint: (id) => `/api/v1/setup/reinsurance-companies/${id}`,
    invalidateKey: ['setup', 'reinsurance-companies'],
    entityLabel: 'Reinsurer',
    entityName: (r) => r.name,
  });

  const query = useQuery<ReinsuranceCompanyDto[]>({
    queryKey: ['setup', 'reinsurance-companies'],
    queryFn: () => validatedGet('/api/v1/setup/reinsurance-companies', z.array(ReinsuranceCompanyDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<ReinsuranceCompanyDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Reinsurer" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'country',  header: 'Country',   cell: ({ getValue }) => <Badge variant="outline" className="text-xs">{getValue() as string}</Badge> },
    { accessorKey: 'rcNumber', header: 'RC Number', cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'email',    header: 'Email',     cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Reinsurer</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No reinsurers yet" description="Add reinsurance counter-parties to enable treaty + FAC cover setup (Module 6)." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search reinsurers…' }} />
      )}
      <ReinsurerSheet open={sheetOpen} onOpenChange={setSheetOpen} reinsurer={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Insurance Companies ──────────────────────────────────────────────────────

function InsurersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<InsuranceCompanyDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<InsuranceCompanyDto>({
    endpoint: (id) => `/api/v1/setup/insurance-companies/${id}`,
    invalidateKey: ['setup', 'insurance-companies'],
    entityLabel: 'Insurance Company',
    entityName: (i) => i.name,
  });

  const query = useQuery<InsuranceCompanyDto[]>({
    queryKey: ['setup', 'insurance-companies'],
    queryFn: () => validatedGet('/api/v1/setup/insurance-companies', z.array(InsuranceCompanyDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<InsuranceCompanyDto>[] = [
    { accessorKey: 'name',          header: ({ column }) => <DataTableColumnHeader column={column} title="Insurance Company" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'naicomLicense', header: 'NAICOM License', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'rcNumber',      header: 'RC Number',      cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',         header: 'Phone',          cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Insurance Company</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No insurers yet" description="Add insurance counter-parties to support coinsurance participant tracking (Module 3)." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search insurers…' }} />
      )}
      <InsurerSheet open={sheetOpen} onOpenChange={setSheetOpen} insurer={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Branches ─────────────────────────────────────────────────────────────────

function BranchesTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<BranchDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<BranchDto>({
    endpoint: (id) => `/api/v1/setup/branches/${id}`,
    invalidateKey: ['setup', 'branches'],
    entityLabel: 'Branch',
    entityName: (b) => b.name,
  });

  const query = useQuery<BranchDto[]>({
    queryKey: ['setup', 'branches'],
    queryFn: () => validatedGet('/api/v1/setup/branches', z.array(BranchDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<BranchDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Branch" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    { accessorKey: 'sbuName', header: 'Parent SBU', cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'address', header: 'Address',    cell: ({ getValue }) => <span className="text-sm text-muted-foreground line-clamp-1">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Branch</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No branches yet" description="Branches roll up to SBUs. Add at least one to start scoping policies." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search branches…' }} />
      )}
      <BranchSheet open={sheetOpen} onOpenChange={setSheetOpen} branch={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── SBUs ─────────────────────────────────────────────────────────────────────

function SbusTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<SbuDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<SbuDto>({
    endpoint: (id) => `/api/v1/setup/sbus/${id}`,
    invalidateKey: ['setup', 'sbus'],
    entityLabel: 'SBU',
    entityName: (s) => s.name,
  });

  const query = useQuery<SbuDto[]>({
    queryKey: ['setup', 'sbus'],
    queryFn: () => validatedGet('/api/v1/setup/sbus', z.array(SbuDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<SbuDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="SBU" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add SBU</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No SBUs yet" description="Strategic Business Units group branches for portfolio-level reporting." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search SBUs…' }} />
      )}
      <SbuSheet open={sheetOpen} onOpenChange={setSheetOpen} sbu={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Surveyors ────────────────────────────────────────────────────────────────

const surveyorTypeVariant: Record<SurveyorDto['type'], 'default' | 'outline'> = { INTERNAL: 'default', EXTERNAL: 'outline' };

function SurveyorsTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<SurveyorDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<SurveyorDto>({
    endpoint: (id) => `/api/v1/setup/surveyors/${id}`,
    invalidateKey: ['setup', 'surveyors'],
    entityLabel: 'Surveyor',
    entityName: (s) => s.name,
  });

  const query = useQuery<SurveyorDto[]>({
    queryKey: ['setup', 'surveyors'],
    queryFn: () => validatedGet('/api/v1/setup/surveyors', z.array(SurveyorDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<SurveyorDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Surveyor" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    {
      accessorKey: 'type', header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as SurveyorDto['type'];
        return <Badge variant={surveyorTypeVariant[t]} className="text-xs">{t.toLowerCase()}</Badge>;
      },
    },
    { accessorKey: 'licenseNumber', header: 'NAICOM License', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',         header: 'Phone',          cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Surveyor</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No surveyors yet" description="Surveyors handle pre-loss inspections + claim inspections." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search surveyors…' }} />
      )}
      <SurveyorSheet open={sheetOpen} onOpenChange={setSheetOpen} surveyor={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Adjusters ────────────────────────────────────────────────────────────────

const adjusterTypeVariant: Record<AdjusterDto['type'], 'default' | 'outline'> = { INTERNAL: 'default', EXTERNAL: 'outline' };

function AdjustersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<AdjusterDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<AdjusterDto>({
    endpoint: (id) => `/api/v1/setup/adjusters/${id}`,
    invalidateKey: ['setup', 'adjusters'],
    entityLabel: 'Adjuster',
    entityName: (a) => a.name,
  });

  const query = useQuery<AdjusterDto[]>({
    queryKey: ['setup', 'adjusters'],
    queryFn: () => validatedGet('/api/v1/setup/adjusters', z.array(AdjusterDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<AdjusterDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Adjuster" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    {
      accessorKey: 'type', header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as AdjusterDto['type'];
        return <Badge variant={adjusterTypeVariant[t]} className="text-xs">{t.toLowerCase()}</Badge>;
      },
    },
    { accessorKey: 'licenseNumber', header: 'NAICOM License', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',         header: 'Phone',          cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Adjuster</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No adjusters yet" description="Loss adjusters handle post-loss claim assessment. Add NAICOM-licensed firms or internal staff." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search adjusters…' }} />
      )}
      <AdjusterSheet open={sheetOpen} onOpenChange={setSheetOpen} adjuster={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Agents ───────────────────────────────────────────────────────────────────

const agentTypeVariant: Record<AgentDto['type'], 'default' | 'outline'> = { INDIVIDUAL: 'default', CORPORATE: 'outline' };

function AgentsTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<AgentDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<AgentDto>({
    endpoint: (id) => `/api/v1/setup/agents/${id}`,
    invalidateKey: ['setup', 'agents'],
    entityLabel: 'Agent',
    entityName: (a) => a.name,
  });

  const query = useQuery<AgentDto[]>({
    queryKey: ['setup', 'agents'],
    queryFn: () => validatedGet('/api/v1/setup/agents', z.array(AgentDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<AgentDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Agent" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    {
      accessorKey: 'type', header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as AgentDto['type'];
        return <Badge variant={agentTypeVariant[t]} className="text-xs">{t.toLowerCase()}</Badge>;
      },
    },
    { accessorKey: 'licenseNumber', header: 'NAICOM License', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',         header: 'Phone',          cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Agent</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No agents yet" description="NAICOM-licensed insurance agents represent the insurer and earn commission on policies sold. Add individuals or licensed agency firms." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search agents…' }} />
      )}
      <AgentSheet open={sheetOpen} onOpenChange={setSheetOpen} agent={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Relationship Managers ────────────────────────────────────────────────────

function RelationshipManagersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<RelationshipManagerDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<RelationshipManagerDto>({
    endpoint: (id) => `/api/v1/setup/relationship-managers/${id}`,
    invalidateKey: ['setup', 'relationship-managers'],
    entityLabel: 'Relationship Manager',
    entityName: (r) => r.name,
  });

  const query = useQuery<RelationshipManagerDto[]>({
    queryKey: ['setup', 'relationship-managers'],
    queryFn: () => validatedGet('/api/v1/setup/relationship-managers', z.array(RelationshipManagerDtoSchema)),
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<RelationshipManagerDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Relationship Manager" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'branchName', header: 'Branch', cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'email',      header: 'Email',  cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{(getValue() as string) || '—'}</span> },
    { accessorKey: 'phone',      header: 'Phone',  cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) || '—'}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Relationship Manager</Button>
      </div>
      {query.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No relationship managers yet" description="Internal staff who own customer relationships. Set up here, then assign at customer onboarding." />
      ) : (
        <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search relationship managers…' }} />
      )}
      <RelationshipManagerSheet open={sheetOpen} onOpenChange={setSheetOpen} rm={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function OrganisationsPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Organisations"
        description="Manage brokers, agents, reinsurers, insurers, branches, SBUs, surveyors, adjusters and relationship managers."
      />
      <Tabs defaultValue="brokers">
        <TabsList className="mb-4 flex-wrap h-auto">
          <TabsTrigger value="brokers">Brokers</TabsTrigger>
          <TabsTrigger value="agents">Agents</TabsTrigger>
          <TabsTrigger value="reinsurers">Reinsurers</TabsTrigger>
          <TabsTrigger value="insurers">Insurers</TabsTrigger>
          <TabsTrigger value="branches">Branches</TabsTrigger>
          <TabsTrigger value="sbus">SBUs</TabsTrigger>
          <TabsTrigger value="surveyors">Surveyors</TabsTrigger>
          <TabsTrigger value="adjusters">Adjusters</TabsTrigger>
          <TabsTrigger value="relationship-managers">Relationship Managers</TabsTrigger>
        </TabsList>
        <TabsContent value="brokers"><BrokersTab /></TabsContent>
        <TabsContent value="agents"><AgentsTab /></TabsContent>
        <TabsContent value="reinsurers"><ReinsurersTab /></TabsContent>
        <TabsContent value="insurers"><InsurersTab /></TabsContent>
        <TabsContent value="branches"><BranchesTab /></TabsContent>
        <TabsContent value="sbus"><SbusTab /></TabsContent>
        <TabsContent value="surveyors"><SurveyorsTab /></TabsContent>
        <TabsContent value="adjusters"><AdjustersTab /></TabsContent>
        <TabsContent value="relationship-managers"><RelationshipManagersTab /></TabsContent>
      </Tabs>
    </div>
  );
}
