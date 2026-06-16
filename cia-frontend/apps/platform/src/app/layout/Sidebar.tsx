import { NavLink } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  DashboardSquare01Icon, Building06Icon, Audit01Icon, UserShield01Icon, Logout01Icon,
} from '@hugeicons/core-free-icons';
import { cn } from '@cia/ui';
import { useAuth } from '@cia/auth';
import type React from 'react';

type HugeIcon = React.ComponentProps<typeof HugeiconsIcon>['icon'];
interface NavItem { label: string; path: string; icon: HugeIcon; }

const NAV: NavItem[] = [
  { label: 'Dashboard',    path: '/dashboard',    icon: DashboardSquare01Icon },
  { label: 'Tenants',      path: '/tenants',      icon: Building06Icon },
  { label: 'Audit log',    path: '/audit',        icon: Audit01Icon },
  { label: 'Super-admins', path: '/super-admins', icon: UserShield01Icon },
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  return (
    <aside className="flex h-full w-full flex-col bg-card" style={{ boxShadow: '1px 0 0 var(--border)' }}>
      <div className="flex h-[var(--topbar-height,56px)] shrink-0 items-center gap-2.5 px-4"
           style={{ boxShadow: '0 1px 0 var(--border)' }}>
        <span className="font-display text-[17px] font-semibold tracking-tight text-foreground">◈ NubSure Platform</span>
      </div>
      <nav className="flex-1 overflow-y-auto px-3 py-4">
        <ul className="space-y-0.5">
          {NAV.map((item) => (
            <li key={item.path}>
              <NavLink
                to={item.path}
                className={({ isActive }) => cn(
                  'flex items-center gap-2.5 rounded-md px-2.5 py-2 text-[15px] font-medium transition-colors',
                  isActive ? 'bg-secondary text-primary' : 'text-muted-foreground hover:bg-secondary hover:text-foreground',
                )}
              >
                {({ isActive }) => (
                  <>
                    <HugeiconsIcon icon={item.icon} size={18} color="currentColor" strokeWidth={isActive ? 2 : 1.75} />
                    <span>{item.label}</span>
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="shrink-0 px-3 py-3" style={{ boxShadow: '0 -1px 0 var(--border)' }}>
        <div className="flex items-center gap-3 px-2.5 py-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
            {user?.name?.charAt(0).toUpperCase() ?? 'S'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-foreground">{user?.name ?? 'Super-admin'}</p>
            <p className="truncate text-xs text-muted-foreground">{user?.email ?? ''}</p>
          </div>
          <button onClick={logout} className="shrink-0 text-muted-foreground hover:text-foreground transition-colors" aria-label="Sign out">
            <HugeiconsIcon icon={Logout01Icon} size={16} color="currentColor" strokeWidth={1.75} />
          </button>
        </div>
      </div>
    </aside>
  );
}
