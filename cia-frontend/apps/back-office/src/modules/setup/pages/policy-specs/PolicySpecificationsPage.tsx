import { PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';
import ClauseBankTab from './ClauseBankTab';
import TemplatesTab from './TemplatesTab';

export default function PolicySpecificationsPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Policy Specifications"
        description="Manage the clause library and document templates used across all products."
      />
      <Tabs defaultValue="clauses">
        <TabsList>
          <TabsTrigger value="clauses">Clause Bank</TabsTrigger>
          <TabsTrigger value="templates">Templates</TabsTrigger>
        </TabsList>
        <TabsContent value="clauses" className="mt-4">
          <ClauseBankTab />
        </TabsContent>
        <TabsContent value="templates" className="mt-4">
          <TemplatesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
