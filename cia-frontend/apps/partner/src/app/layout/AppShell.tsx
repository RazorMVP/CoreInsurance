import { Outlet } from 'react-router-dom';
import { AppContextProvider, useSelectedApp } from '../AppContext';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

function EmptyOrOutlet() {
  const { isEmpty } = useSelectedApp();
  if (isEmpty) {
    return (
      <div className="p-10 text-center text-sm text-muted-foreground">
        No Partner Apps are granted to your account yet. Ask the insurer’s admin to invite you.
      </div>
    );
  }
  return <Outlet />;
}

export function AppShell() {
  return (
    <AppContextProvider>
      <div className="flex h-screen overflow-hidden bg-background">
        <aside style={{ width: 256, flexShrink: 0 }}><Sidebar /></aside>
        <div className="flex flex-1 flex-col overflow-hidden">
          <Topbar />
          <main className="flex-1 overflow-y-auto"><div className="p-6"><EmptyOrOutlet /></div></main>
        </div>
      </div>
    </AppContextProvider>
  );
}
