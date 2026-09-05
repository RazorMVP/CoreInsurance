// Shared empty state for per-app pages (Credentials / Webhooks / Usage / Explorer)
// rendered when no Partner App is selected yet. With ≥2 apps and no stored
// selection, `selectedAppId` is null and the per-app queries are `enabled: false`
// — react-query v5 reports `isLoading === false` for a disabled query, so without
// this notice the page would render a bare heading with no content and no hint.
export function SelectAppNotice() {
  return (
    <div className="rounded-lg border border-dashed border-border bg-card p-6 text-sm text-muted-foreground">
      Select a Partner App from the menu above to continue.
    </div>
  );
}
