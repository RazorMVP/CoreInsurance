import { Button, EmptyState, PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';

function PlaceholderTab({ label }: { label: string }) {
  return <EmptyState title={`No ${label} yet`} action={<Button size="sm">Add {label}</Button>} />;
}

export default function VehicleRegistryPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader title="Vehicle Registry" description="Manage vehicle makes, models and types used in motor class underwriting." />
      <Tabs defaultValue="makes">
        <TabsList>
          <TabsTrigger value="makes">Makes</TabsTrigger>
          <TabsTrigger value="models">Models</TabsTrigger>
          <TabsTrigger value="types">Types</TabsTrigger>
        </TabsList>
        <TabsContent value="makes" className="mt-4"><PlaceholderTab label="vehicle make" /></TabsContent>
        <TabsContent value="models" className="mt-4"><PlaceholderTab label="vehicle model" /></TabsContent>
        <TabsContent value="types" className="mt-4"><PlaceholderTab label="vehicle type" /></TabsContent>
      </Tabs>
    </div>
  );
}
