import { NavLink, useLocation } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  DashboardSquare01Icon,
  UserGroupIcon,
  NoteEditIcon,
  Shield01Icon,
  FileEditIcon,
  AlertCircleIcon,
  Money01Icon,
  RepeatIcon,
  Setting06Icon,
  Audit01Icon,
  Logout01Icon,
  Menu01Icon,
} from '@hugeicons/core-free-icons';
import { cn } from '@cia/ui';
import { useAuth } from '@cia/auth';
import type React from 'react';

type HugeIcon = React.ComponentProps<typeof HugeiconsIcon>['icon'];

interface NavItem {
  label: string;
  path: string;
  icon: HugeIcon;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
}

const navGroups: NavGroup[] = [
  {
    label: 'Operations',
    items: [
      { label: 'Dashboard',    path: '/dashboard',    icon: DashboardSquare01Icon },
      { label: 'Customers',    path: '/customers',    icon: UserGroupIcon },
      { label: 'Quotation',    path: '/quotation',    icon: NoteEditIcon },
      { label: 'Policies',     path: '/policies',     icon: Shield01Icon },
      { label: 'Endorsements', path: '/endorsements', icon: FileEditIcon },
      { label: 'Claims',       path: '/claims',       icon: AlertCircleIcon },
    ],
  },
  {
    label: 'Finance & RI',
    items: [
      { label: 'Finance',     path: '/finance',     icon: Money01Icon },
      { label: 'Reinsurance', path: '/reinsurance', icon: RepeatIcon },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Setup', path: '/setup', icon: Setting06Icon },
      { label: 'Audit', path: '/audit', icon: Audit01Icon },
    ],
  },
];

export default function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const { user, logout } = useAuth();
  const location = useLocation();

  return (
    <aside
      className="flex h-full w-full flex-col bg-card"
      style={{ boxShadow: '1px 0 0 var(--border)' }}
    >
      {/* Logo + hamburger toggle */}
      <div
        className={cn(
          'flex h-[var(--topbar-height)] shrink-0 items-center gap-2.5',
          collapsed ? 'justify-center px-0' : 'px-3'
        )}
        style={{ boxShadow: '0 1px 0 var(--border)' }}
      >
        <img
          src="/logo.png"
          alt="NubSure"
          className="shrink-0 rounded-full"
          style={{ width: 28, height: 28 }}
        />
        {!collapsed && (
          <>
            <span className="flex-1 font-display text-[17px] font-semibold tracking-tight text-foreground whitespace-nowrap">
              NubSure
            </span>
            <button
              onClick={onToggle}
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
              aria-label="Collapse sidebar"
            >
              <HugeiconsIcon icon={Menu01Icon} size={17} color="currentColor" strokeWidth={1.75} />
            </button>
          </>
        )}
        {collapsed && (
          <button
            onClick={onToggle}
            className="mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
            aria-label="Expand sidebar"
          >
            <HugeiconsIcon icon={Menu01Icon} size={17} color="currentColor" strokeWidth={1.75} />
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto overflow-x-hidden scrollbar-thin py-4"
        style={{ paddingLeft: collapsed ? 8 : 12, paddingRight: collapsed ? 8 : 12 }}
      >
        {navGroups.map((group) => {
          const hasActive = group.items.some(item =>
            location.pathname.startsWith(item.path)
          );
          return (
            <div key={group.label} className="mb-5">
              {!collapsed && (
                <p className={cn(
                  'mb-1 px-2 text-[11px] font-semibold uppercase tracking-widest transition-colors whitespace-nowrap',
                  hasActive ? 'text-primary' : 'text-muted-foreground'
                )}>
                  {group.label}
                </p>
              )}
              <ul className="space-y-0.5">
                {group.items.map((item) => (
                  <li key={item.path}>
                    <NavLink
                      to={item.path}
                      title={collapsed ? item.label : undefined}
                      className={({ isActive }) =>
                        cn(
                          'flex items-center rounded-md py-2 text-[15px] font-medium transition-colors',
                          collapsed
                            ? 'justify-center px-2'
                            : 'gap-2.5 px-2.5',
                          isActive
                            ? 'bg-teal-50 text-primary'
                            : 'text-charcoal-600 hover:bg-secondary hover:text-foreground'
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          <HugeiconsIcon
                            icon={item.icon}
                            size={18}
                            color="currentColor"
                            strokeWidth={isActive ? 2 : 1.75}
                          />
                          {!collapsed && (
                            <span className="whitespace-nowrap">{item.label}</span>
                          )}
                        </>
                      )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>

      {/* User */}
      <div
        className={cn(
          'shrink-0 py-3',
          collapsed ? 'px-2' : 'px-3'
        )}
        style={{ boxShadow: '0 -1px 0 var(--border)' }}
      >
        <div className={cn(
          'flex items-center rounded-md',
          collapsed ? 'justify-center p-1.5' : 'gap-3 px-2.5 py-2'
        )}>
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
            {user?.name?.charAt(0).toUpperCase() ?? 'U'}
          </div>

          {!collapsed && (
            <>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-foreground">{user?.name ?? 'User'}</p>
                <p className="truncate text-xs text-muted-foreground">{user?.email ?? ''}</p>
              </div>
              <button
                onClick={logout}
                className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
                aria-label="Sign out"
              >
                <HugeiconsIcon icon={Logout01Icon} size={16} color="currentColor" strokeWidth={1.75} />
              </button>
            </>
          )}
        </div>
      </div>
    </aside>
  );
}
