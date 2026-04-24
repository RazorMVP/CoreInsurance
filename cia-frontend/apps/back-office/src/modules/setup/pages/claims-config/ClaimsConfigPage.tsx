import { Button, EmptyState, PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';

function PlaceholderTab({ label }: { label: string }) {
  return <EmptyState title={`No ${label} configured`} action={<Button size="sm">Add</Button>} />;
}

export default function ClaimsConfigPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader title="Claims Configuration" description="Set up reserve categories, notification timelines, document requirements and loss types." />
      <Tabs defaultValue="reserves">
        <TabsList>
          <TabsTrigger value="reserves">Reserve Categories</TabsTrigger>
          <TabsTrigger value="timelines">Notification Timelines</TabsTrigger>
          <TabsTrigger value="documents">Required Documents</TabsTrigger>
          <TabsTrigger value="loss">Nature / Cause of Loss</TabsTrigger>
        </TabsList>
        <TabsContent value="reserves"  className="mt-4"><PlaceholderTab label="reserve category" /></TabsContent>
        <TabsContent value="timelines" className="mt-4"><PlaceholderTab label="notification timeline" /></TabsContent>
        <TabsContent value="documents" className="mt-4"><PlaceholderTab label="document requirement" /></TabsContent>
        <TabsContent value="loss"      className="mt-4"><PlaceholderTab label="loss type" /></TabsContent>
      </Tabs>
    </div>
  );
}
