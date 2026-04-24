import { PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';
import TreatiesTab    from './treaties/TreatiesTab';
import AllocationsTab from './allocations/AllocationsTab';
import FACTab         from './fac/FACTab';
import ReportsTab     from './reports/ReportsTab';

export default function ReinsurancePage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Reinsurance"
        description="Manage treaties, automatic allocations, facultative covers and bordereaux reporting."
      />
      <Tabs defaultValue="treaties">
        <TabsList>
          <TabsTrigger value="treaties">Treaties</TabsTrigger>
          <TabsTrigger value="allocations">Allocations</TabsTrigger>
          <TabsTrigger value="fac">Facultative</TabsTrigger>
          <TabsTrigger value="reports">Returns & Reports</TabsTrigger>
        </TabsList>
        <TabsContent value="treaties"    className="mt-4"><TreatiesTab /></TabsContent>
        <TabsContent value="allocations" className="mt-4"><AllocationsTab /></TabsContent>
        <TabsContent value="fac"         className="mt-4"><FACTab /></TabsContent>
        <TabsContent value="reports"     className="mt-4"><ReportsTab /></TabsContent>
      </Tabs>
    </div>
  );
}
