import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Toaster } from '@cia/ui';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

const isDemo = import.meta.env.VITE_DEMO_MODE === 'true';

export default function AppShell() {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="flex h-full flex-col overflow-hidden bg-background">
      {isDemo && (
        <div className="flex items-center justify-center gap-2 bg-amber-100 px-4 py-1.5 text-xs font-medium text-amber-900 border-b border-amber-200">
          <span className="rounded-sm bg-amber-200 px-1.5 py-0.5 font-semibold uppercase tracking-wide text-[10px]">Demo</span>
          <span>Stakeholder preview — auth is mocked, data is illustrative. Not a tenant environment.</span>
        </div>
      )}
      <div className="flex flex-1 overflow-hidden">
        <aside
          style={{
            width: collapsed ? '64px' : '256px',
            transition: 'width 220ms cubic-bezier(0.16, 1, 0.3, 1)',
            flexShrink: 0,
            overflow: 'hidden',
          }}
        >
          <Sidebar collapsed={collapsed} onToggle={() => setCollapsed(c => !c)} />
        </aside>

        <div className="flex flex-1 flex-col overflow-hidden">
          <Topbar />
          <main className="flex-1 overflow-y-auto scrollbar-thin">
            <div className="page-enter">
              <Outlet />
            </div>
          </main>
        </div>
      </div>
      <Toaster />
    </div>
  );
}
