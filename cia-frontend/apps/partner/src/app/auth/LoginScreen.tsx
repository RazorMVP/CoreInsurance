export function LoginScreen({ apiBase }: { apiBase: string }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 text-center">
        <h1 className="font-display text-2xl font-bold text-foreground">CIA Partner Portal</h1>
        <p className="mt-2 text-sm text-muted-foreground">Sign in to manage your Insurtech integration.</p>
        <a
          href={`${apiBase}/portal/auth/login`}
          className="mt-6 inline-flex w-full items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90"
        >
          Sign in
        </a>
      </div>
    </div>
  );
}
