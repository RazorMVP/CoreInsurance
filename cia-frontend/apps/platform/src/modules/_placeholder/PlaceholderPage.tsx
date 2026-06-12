export default function PlaceholderPage({ title }: { title: string }) {
  return (
    <div className="p-6">
      <h1 className="font-display text-xl font-semibold text-foreground">{title}</h1>
      <p className="mt-2 text-sm text-muted-foreground">Coming in Phase 3.</p>
    </div>
  );
}
