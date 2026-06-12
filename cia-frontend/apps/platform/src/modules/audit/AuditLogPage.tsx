import { useState } from 'react';
import { Button, Input, PageHeader, PageSection, Skeleton } from '@cia/ui';
import { usePlatformAudit } from '@cia/api-client';
import AuditTable from './AuditTable';
import ServerPaginationFooter from '../../components/ServerPaginationFooter';

export default function AuditLogPage() {
  const [page, setPage] = useState(0);
  const [filterInput, setFilterInput] = useState('');
  const [targetSchema, setTargetSchema] = useState<string | undefined>(undefined);
  const size = 50;
  const auditQuery = usePlatformAudit(page, size, targetSchema);

  const rows = auditQuery.data?.data ?? [];
  const meta = auditQuery.data?.meta;

  function applyFilter(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setTargetSchema(filterInput.trim() || undefined);
  }

  return (
    <div className="p-6">
      <PageHeader title="Audit log" description="Every platform mutation — onboarding, suspend/activate, super-admin changes." />

      <form onSubmit={applyFilter} className="mt-4 flex gap-2">
        <Input
          value={filterInput}
          onChange={(e) => setFilterInput(e.target.value)}
          placeholder="Filter by tenant schema (e.g. tenant_acme)…"
          className="max-w-xs"
        />
        <Button type="submit" variant="outline">Filter</Button>
        {targetSchema && (
          <Button type="button" variant="ghost" onClick={() => { setFilterInput(''); setTargetSchema(undefined); setPage(0); }}>
            Clear
          </Button>
        )}
      </form>

      <PageSection className="mt-4">
        {auditQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <>
            <AuditTable rows={rows} />
            {meta && <ServerPaginationFooter page={page} size={size} total={meta.total} onPageChange={setPage} noun="events" />}
          </>
        )}
      </PageSection>
    </div>
  );
}
