import { useState } from 'react';
import {
  Badge, Button,
  DataTable, DataTableRowActions,
  EmptyState, PageSection, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import CreateFACOfferSheet from './CreateFACOfferSheet';
import AddInwardFACSheet   from './AddInwardFACSheet';

// Outward FAC
interface FacOutwardDto {
  id:           string;
  reference:    string;
  policyNumber: string;
  reinsurer:    string;
  sumInsured:   number;
  premiumRate:  number;
  status:       'OFFER_SENT' | 'ACCEPTED' | 'DECLINED' | 'DRAFT';
  offerDate:    string;
}

// Inward FAC
interface FacInwardDto {
  id:           string;
  reference:    string;
  cedingCompany:string;
  classOfBusiness:string;
  sumInsured:   number;
  ourShare:     number;
  ourPremium:   number;
  startDate:    string;
  endDate:      string;
  status:       'ACTIVE' | 'RENEWED' | 'EXPIRED';
}

const mockOutward: FacOutwardDto[] = [
  { id: 'fo1', reference: 'FAC-OUT-2026-001', policyNumber: 'POL-2026-00005', reinsurer: 'Munich Re',        sumInsured: 26_000_000, premiumRate: 0.8, status: 'ACCEPTED',    offerDate: '2026-02-15' },
  { id: 'fo2', reference: 'FAC-OUT-2026-002', policyNumber: 'POL-2026-00006', reinsurer: "Lloyd's Syndicate",sumInsured: 45_000_000, premiumRate: 1.2, status: 'OFFER_SENT',  offerDate: '2026-03-10' },
];

const mockInward: FacInwardDto[] = [
  { id: 'fi1', reference: 'FAC-IN-2026-001', cedingCompany: 'Leadway Assurance', classOfBusiness: 'Fire & Burglary', sumInsured: 20_000_000, ourShare: 30, ourPremium: 48_000, startDate: '2026-01-01', endDate: '2027-01-01', status: 'ACTIVE' },
  { id: 'fi2', reference: 'FAC-IN-2026-002', cedingCompany: 'AIICO Insurance',   classOfBusiness: 'Marine Cargo',    sumInsured: 12_000_000, ourShare: 25, ourPremium: 22_500, startDate: '2025-07-01', endDate: '2026-07-01', status: 'EXPIRED' },
];

const outSt: Record<FacOutwardDto['status'], 'active'|'pending'|'rejected'|'draft'> = { ACCEPTED: 'active', OFFER_SENT: 'pending', DECLINED: 'rejected', DRAFT: 'draft' };
const inSt:  Record<FacInwardDto['status'], 'active'|'pending'|'cancelled'> = { ACTIVE: 'active', RENEWED: 'pending', EXPIRED: 'cancelled' };

export default function FACTab() {
  const [facOfferOpen,  setFacOfferOpen]  = useState(false);
  const [inwardFACOpen, setInwardFACOpen] = useState(false);
  const outColumns: ColumnDef<FacOutwardDto>[] = [
    { accessorKey: 'reference',    header: 'Reference',   cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span> },
    { accessorKey: 'policyNumber', header: 'Policy',      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span> },
    { accessorKey: 'reinsurer',    header: 'Reinsurer',   cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span> },
    { accessorKey: 'sumInsured',   header: 'Sum Insured', cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span> },
    { accessorKey: 'premiumRate',  header: 'Rate %',      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span> },
    { accessorKey: 'status',       header: 'Status',      cell: ({ getValue }) => { const s = getValue() as FacOutwardDto['status']; return <Badge variant={outSt[s]} className="text-[10px]">{s.replace('_',' ').toLowerCase()}</Badge>; } },
    { accessorKey: 'offerDate',    header: 'Offer Date',  cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span> },
    {
      id: 'actions', cell: ({ row }) => (
        <DataTableRowActions row={row as Row<FacOutwardDto>} actions={[
          ...(row.original.status === 'ACCEPTED' ? [{ label: 'Generate credit note', onClick: () => {} }] : []),
          { label: 'Download offer slip', onClick: () => {} },
          { label: 'Cancel FAC', onClick: () => {}, separator: true, className: 'text-destructive' },
        ]} />
      ),
    },
  ];

  const inColumns: ColumnDef<FacInwardDto>[] = [
    { accessorKey: 'reference',     header: 'Reference',     cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span> },
    { accessorKey: 'cedingCompany', header: 'Ceding Company', cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span> },
    { accessorKey: 'classOfBusiness', header: 'Class',        cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span> },
    { accessorKey: 'sumInsured',    header: 'Sum Insured',    cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span> },
    { accessorKey: 'ourShare',      header: 'Our Share',      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as number}%</span> },
    { accessorKey: 'ourPremium',    header: 'Our Premium',    cell: ({ getValue }) => <span className="text-sm tabular-nums text-primary">₦{(getValue() as number).toLocaleString()}</span> },
    { accessorKey: 'status',        header: 'Status',         cell: ({ getValue }) => { const s = getValue() as FacInwardDto['status']; return <Badge variant={inSt[s]} className="text-[10px]">{s.toLowerCase()}</Badge>; } },
    {
      id: 'actions', cell: ({ row }) => (
        <DataTableRowActions row={row as Row<FacInwardDto>} actions={[
          ...(row.original.status === 'ACTIVE' ? [{ label: 'Renew', onClick: () => {} }, { label: 'Extend period', onClick: () => {} }] : []),
          { label: row.original.status === 'EXPIRED' ? 'View expired' : 'Cancel', onClick: () => {}, className: 'text-destructive' },
        ]} />
      ),
    },
  ];

  return (
    <>
    <Tabs defaultValue="outward">
      <TabsList>
        <TabsTrigger value="outward">Outward FAC ({mockOutward.length})</TabsTrigger>
        <TabsTrigger value="inward">Inward FAC ({mockInward.length})</TabsTrigger>
      </TabsList>

      <TabsContent value="outward" className="mt-4">
        <PageSection
          title="Outward Facultative"
          description="Risks exceeding treaty capacity placed with reinsurers on a facultative basis."
          actions={<Button size="sm" onClick={() => setFacOfferOpen(true)}>Create FAC Offer</Button>}
        >
          {mockOutward.length === 0
            ? <EmptyState title="No outward FAC covers" description="Create when a risk exceeds treaty gross capacity." />
            : <DataTable columns={outColumns} data={mockOutward} toolbar={{ searchColumn: 'policyNumber', searchPlaceholder: 'Search…' }} />
          }
        </PageSection>
      </TabsContent>

      <TabsContent value="inward" className="mt-4">
        <PageSection
          title="Inward Facultative"
          description="Facultative risks accepted from other ceding companies."
          actions={<Button size="sm" onClick={() => setInwardFACOpen(true)}>Add Inward FAC</Button>}
        >
          {mockInward.length === 0
            ? <EmptyState title="No inward FAC policies" />
            : <DataTable columns={inColumns} data={mockInward} toolbar={{ searchColumn: 'cedingCompany', searchPlaceholder: 'Search…' }} />
          }
        </PageSection>
      </TabsContent>
    </Tabs>

    <CreateFACOfferSheet
      open={facOfferOpen}
      onOpenChange={setFacOfferOpen}
      onSuccess={() => setFacOfferOpen(false)}
    />
    <AddInwardFACSheet
      open={inwardFACOpen}
      onOpenChange={setInwardFACOpen}
      onSuccess={() => setInwardFACOpen(false)}
    />
    </>
  );
}
