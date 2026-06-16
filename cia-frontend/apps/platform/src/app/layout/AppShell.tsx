import { Outlet } from 'react-router-dom';
import { Toaster } from '@cia/ui';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

const isDemo = import.meta.env.VITE_DEMO_MODE === 'true';

export default function AppShell() {
  return (
    <div className="flex h-full flex-col overflow-hidden bg-background">
      {isDemo && (
        <div className="flex items-center justify-center gap-2 border-b border-amber-700/40 bg-amber-900/30 px-4 py-1.5 text-xs font-medium text-amber-200">
          <span className="rounded-sm bg-amber-700/40 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide">Demo</span>
          <span>Stakeholder preview — auth is mocked, data is illustrative. Not a live platform.</span>
        </div>
      )}
      <div className="flex flex-1 overflow-hidden">
        <aside style={{ width: 256, flexShrink: 0 }}>
          <Sidebar />
        </aside>
        <div className="flex flex-1 flex-col overflow-hidden">
          <Topbar />
          <main className="flex-1 overflow-y-auto scrollbar-thin">
            <div className="page-enter"><Outlet /></div>
          </main>
        </div>
      </div>
      <Toaster />
    </div>
  );
}
