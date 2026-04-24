import { PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';
import ReceivablesTab from './receivables/ReceivablesTab';
import PayablesTab    from './payables/PayablesTab';

export default function FinancePage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Finance"
        description="Manage receipts against debit notes and payments against credit notes."
      />
      <Tabs defaultValue="receivables">
        <TabsList>
          <TabsTrigger value="receivables">Receivables</TabsTrigger>
          <TabsTrigger value="payables">Payables</TabsTrigger>
        </TabsList>
        <TabsContent value="receivables" className="mt-4">
          <ReceivablesTab />
        </TabsContent>
        <TabsContent value="payables" className="mt-4">
          <PayablesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
