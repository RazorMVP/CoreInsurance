import { useLocation } from 'react-router-dom';

const LABELS: Record<string, string> = {
  dashboard: 'Dashboard', tenants: 'Tenants', audit: 'Audit log', 'super-admins': 'Super-admins',
};

export default function Topbar() {
  const seg = useLocation().pathname.split('/').filter(Boolean)[0] ?? 'dashboard';
  return (
    <header className="flex h-[var(--topbar-height,56px)] shrink-0 items-center gap-3 bg-card px-4"
            style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <h1 className="font-display text-[15px] font-semibold tracking-tight text-foreground">{LABELS[seg] ?? seg}</h1>
    </header>
  );
}
