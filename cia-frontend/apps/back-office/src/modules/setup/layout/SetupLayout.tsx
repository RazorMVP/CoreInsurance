import { cn } from '@cia/ui';
import { NavLink } from 'react-router-dom';

interface NavItem { label: string; path: string }

const navGroups: { label: string; items: NavItem[] }[] = [
  {
    label: 'Company',
    items: [
      { label: 'Company Settings',  path: '/setup/company' },
    ],
  },
  {
    label: 'People & Access',
    items: [
      { label: 'Users',             path: '/setup/users' },
      { label: 'Access Groups',     path: '/setup/access-groups' },
      { label: 'Approval Groups',   path: '/setup/approval-groups' },
    ],
  },
  {
    label: 'Products',
    items: [
      { label: 'Classes of Business',  path: '/setup/classes' },
      { label: 'Products',             path: '/setup/products' },
      { label: 'Policy Specifications', path: '/setup/policy-specifications' },
    ],
  },
  {
    label: 'Organisations',
    items: [
      { label: 'Organisations',     path: '/setup/organisations' },
      { label: 'Vehicle Registry',  path: '/setup/vehicle-registry' },
    ],
  },
  {
    label: 'Customers',
    items: [
      { label: 'Customer Number Format', path: '/setup/customer-number-format' },
    ],
  },
  {
    label: 'Claims',
    items: [
      { label: 'Claims Configuration', path: '/setup/claims-config' },
    ],
  },
  {
    label: 'Integrations',
    items: [
      { label: 'Partner Apps',      path: '/setup/partner-apps' },
    ],
  },
];

export default function SetupLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-full">
      {/* Secondary nav */}
      <aside
        className="hidden w-52 shrink-0 overflow-y-auto bg-card py-4 md:block"
        style={{ boxShadow: '1px 0 0 var(--border)' }}
      >
        {navGroups.map((group) => (
          <div key={group.label} className="mb-5 px-3">
            <p className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
              {group.label}
            </p>
            <ul className="space-y-0.5">
              {group.items.map((item) => (
                <li key={item.path}>
                  <NavLink
                    to={item.path}
                    className={({ isActive }) =>
                      cn(
                        'block rounded-md px-2.5 py-1.5 text-[13px] font-medium transition-colors',
                        isActive
                          ? 'bg-teal-50 text-primary'
                          : 'text-charcoal-600 hover:bg-secondary hover:text-foreground',
                      )
                    }
                  >
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </aside>

      {/* Page content */}
      <main className="flex-1 overflow-y-auto">
        {children}
      </main>
    </div>
  );
}
