import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { HugeiconsIcon } from '@hugeicons/react';
import { Search01Icon, Shield01Icon, AlertCircleIcon, UserGroupIcon, NoteEditIcon } from '@hugeicons/core-free-icons';
import { cn } from '@cia/ui';
import { useClickOutside } from '../../hooks/useClickOutside';

interface SearchResult {
  id: string;
  type: string;
  label: string;
  sub: string;
  path: string;
}

const TYPE_ICONS: Record<string, React.ComponentProps<typeof HugeiconsIcon>['icon']> = {
  Policy:   Shield01Icon,
  Claim:    AlertCircleIcon,
  Customer: UserGroupIcon,
  Quote:    NoteEditIcon,
};

const TYPE_COLORS: Record<string, string> = {
  Policy:   'text-blue-600',
  Claim:    'text-red-600',
  Customer: 'text-amber-600',
  Quote:    'text-violet-600',
};

function useDebounce(value: string, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

export default function SearchBar() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [open, setOpen]   = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  useClickOutside(wrapRef, () => setOpen(false));

  const debouncedQ = useDebounce(query, 300);

  const { data: results = [], isFetching } = useQuery<SearchResult[]>({
    queryKey: ['search', debouncedQ],
    queryFn: async () => {
      if (debouncedQ.length < 2) return [];
      const res = await apiClient.get<{ data: SearchResult[] }>(
        `/api/v1/dashboard/search?q=${encodeURIComponent(debouncedQ)}`
      );
      return res.data.data;
    },
    enabled: debouncedQ.length >= 2,
    staleTime: 10_000,
  });

  function handleSelect(result: SearchResult) {
    setQuery('');
    setOpen(false);
    navigate(result.path);
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') { setOpen(false); inputRef.current?.blur(); }
  }

  const showDropdown = open && debouncedQ.length >= 2;

  return (
    <div ref={wrapRef} className="relative flex flex-1 items-center">
      <span className="pointer-events-none absolute left-3 text-muted-foreground z-10">
        <HugeiconsIcon icon={Search01Icon} size={15} color="currentColor" strokeWidth={1.75} />
      </span>
      <input
        ref={inputRef}
        type="search"
        value={query}
        onChange={e => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => query.length >= 2 && setOpen(true)}
        onKeyDown={handleKeyDown}
        placeholder="Search policies, claims, customers…"
        className="h-[37px] w-full rounded-md bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        style={{ border: '1px solid var(--border)' }}
        autoComplete="off"
      />

      {/* Results dropdown */}
      {showDropdown && (
        <div
          className="absolute left-0 top-11 z-50 w-full rounded-lg border bg-card shadow-lg overflow-hidden"
          style={{ boxShadow: '0 4px 24px rgba(0,0,0,0.10)' }}
        >
          {isFetching && results.length === 0 ? (
            <p className="px-4 py-3 text-xs text-muted-foreground">Searching…</p>
          ) : results.length === 0 ? (
            <p className="px-4 py-3 text-xs text-muted-foreground">
              No results for "<span className="font-medium">{debouncedQ}</span>"
            </p>
          ) : (
            <ul className="max-h-72 overflow-y-auto divide-y divide-border">
              {results.map(r => {
                const icon = TYPE_ICONS[r.type] ?? Search01Icon;
                const color = TYPE_COLORS[r.type] ?? 'text-muted-foreground';
                return (
                  <li key={r.id}>
                    <button
                      className="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-secondary/50 transition-colors"
                      onClick={() => handleSelect(r)}
                    >
                      <span className={cn('shrink-0', color)}>
                        <HugeiconsIcon icon={icon} size={14} color="currentColor" strokeWidth={1.75} />
                      </span>
                      <span className="flex-1 min-w-0">
                        <span className="block truncate text-sm font-medium text-foreground">{r.label}</span>
                        <span className="block truncate text-xs text-muted-foreground">{r.type} · {r.sub}</span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
