export default function NotAuthorized() {
  return (
    <div className="flex h-full min-h-screen flex-col items-center justify-center gap-3 bg-background p-8 text-center">
      <h1 className="font-display text-2xl font-semibold text-foreground">Not authorized</h1>
      <p className="max-w-md text-sm text-muted-foreground">
        The NubSure Platform console requires a <span className="font-medium text-foreground">SUPER_ADMIN</span> account
        on the platform realm. Your token does not carry that role.
      </p>
    </div>
  );
}
