import { useEffect, useState } from 'react';

/**
 * Returns `value` delayed by `delayMs` (default 300ms) — for search-as-you-type
 * so keystrokes don't each trigger a server fetch. Generic sibling of the
 * string-only `useDebounce` local to SearchBar.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}
