import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface ServerPaginationConfig {
  /** Rows per page when the URL omits `size`. Default 20. */
  defaultSize?:     number;
  /** Options for the page-size selector. Default [10, 20, 50, 100]. */
  pageSizeOptions?: number[];
  /** Sort applied when the URL omits `sort` (and omitted from the URL when equal). e.g. 'createdAt,desc'. */
  defaultSort?:     string;
  /** Per-filter default; a filter whose value equals its default is omitted from the URL. */
  filterDefaults?:  Record<string, string>;
}

export interface ServerPaginationState {
  page:    number;
  size:    number;
  sort:    string | undefined;
  filters: Record<string, string>;
  setPage:      (p: number) => void;
  setSize:      (s: number) => void;
  setSort:      (s: string) => void;
  setFilter:    (key: string, value: string) => void;
  resetFilters: () => void;
  /** Serialize the current state for an API call: `page=..&size=..&sort=..&<filters>`. */
  toQueryString: () => string;
  pageSizeOptions: number[];
}

const RESERVED = new Set(['page', 'size', 'sort']);

/**
 * URL-backed list state for server-side pagination. Reads/writes `page`, `size`,
 * `sort`, and arbitrary filter params from the query string via react-router's
 * `useSearchParams`, so list state is bookmarkable, refresh-safe, and
 * back/forward-navigable. Defaults are omitted from the URL; all writes use
 * `replace` (the list stays a single history entry); any size/sort/filter change
 * resets the page to 0.
 */
export function useServerPagination(config: ServerPaginationConfig = {}): ServerPaginationState {
  const { defaultSize = 20, pageSizeOptions = [10, 20, 50, 100], defaultSort, filterDefaults = {} } = config;
  const [params, setParams] = useSearchParams();

  const page = Number(params.get('page') ?? '0') || 0;
  const size = Number(params.get('size') ?? String(defaultSize)) || defaultSize;
  const sort = params.get('sort') ?? defaultSort;

  const filters = useMemo(() => {
    const out: Record<string, string> = {};
    params.forEach((v, k) => { if (!RESERVED.has(k)) out[k] = v; });
    return out;
  }, [params]);

  const write = (next: { page: number; size: number; sort: string | undefined; filters: Record<string, string> }) => {
    const sp = new URLSearchParams();
    if (next.page !== 0) sp.set('page', String(next.page));
    if (next.size !== defaultSize) sp.set('size', String(next.size));
    if (next.sort && next.sort !== defaultSort) sp.set('sort', next.sort);
    for (const [k, val] of Object.entries(next.filters)) {
      if (val !== '' && val !== (filterDefaults[k] ?? '')) sp.set(k, val);
    }
    setParams(sp, { replace: true });
  };

  const setPage = (p: number) => write({ page: p, size, sort, filters });
  const setSize = (s: number) => write({ page: 0, size: s, sort, filters });
  const setSort = (s: string) => write({ page: 0, size, sort: s, filters });
  const setFilter = (key: string, value: string) =>
    write({ page: 0, size, sort, filters: { ...filters, [key]: value } });
  const resetFilters = () => write({ page: 0, size, sort, filters: {} });

  const toQueryString = () => {
    const sp = new URLSearchParams();
    sp.set('page', String(page));
    sp.set('size', String(size));
    if (sort) sp.set('sort', sort);
    for (const [k, val] of Object.entries(filters)) if (val !== '') sp.set(k, val);
    return sp.toString();
  };

  return { page, size, sort, filters, setPage, setSize, setSort, setFilter, resetFilters, toQueryString, pageSizeOptions };
}
