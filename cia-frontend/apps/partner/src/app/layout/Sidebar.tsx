import { NavLink } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import { Analytics01Icon, KeyIcon, CodeIcon, Notification01Icon } from '@hugeicons/core-free-icons';
import { usePortalAuth } from '../auth/PortalAuthProvider';

const NAV = [
  { label: 'Usage',        path: '/usage',       icon: Analytics01Icon },
  { label: 'Credentials',  path: '/credentials', icon: KeyIcon },
  { label: 'API Explorer', path: '/explorer',    icon: CodeIcon },
  { label: 'Webhooks',     path: '/webhooks',    icon: Notification01Icon },
];

export function Sidebar() {
  const { session, logout } = usePortalAuth();
  return (
    <div className="flex h-full flex-col border-r border-border bg-card">
      <div className="flex items-center gap-2 px-4 py-4">
        <span className="font-display text-lg font-bold text-foreground">Partner Portal</span>
      </div>
      <nav className="flex-1 px-2">
        <ul className="space-y-1">
          {NAV.map((item) => (
            <li key={item.path}>
              <NavLink
                to={item.path}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-md px-3 py-2 text-sm ${isActive ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:bg-muted/40 hover:text-foreground'}`
                }
              >
                <HugeiconsIcon icon={item.icon} size={18} />
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="border-t border-border p-4">
        <p className="truncate text-sm text-foreground">{session.email}</p>
        <button onClick={logout} className="mt-2 text-xs text-muted-foreground hover:text-foreground">Sign out</button>
      </div>
    </div>
  );
}
