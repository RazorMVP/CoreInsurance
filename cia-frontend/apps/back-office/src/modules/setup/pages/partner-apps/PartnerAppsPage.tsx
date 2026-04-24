import { Button, EmptyState, PageHeader } from '@cia/ui';

export default function PartnerAppsPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Partner Apps"
        description="Manage Insurtech partner API credentials, scopes, rate limits and webhook secrets."
        actions={<Button>Register App</Button>}
      />
      <EmptyState
        title="No partner apps registered"
        description="Register an Insurtech partner to generate OAuth2 credentials and configure API access."
        action={<Button>Register App</Button>}
      />
    </div>
  );
}
