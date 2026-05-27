import { useQuery } from '@tanstack/react-query';
import { listRecentDownloads } from '@cia/api-client';

/**
 * Server-side recent PDF downloads for the calling user.
 *
 * 30-second staleTime so opening the panel right after a download still
 * shows the just-written row when refetchOnMount fires.
 *
 * Returns the validated envelope; consumer accesses entries via
 * `query.data?.data ?? []` (matches useReceiptList pattern).
 */
export function useRecentDownloads(days = 1) {
  return useQuery({
    queryKey: ['finance', 'pdf-downloads', days],
    queryFn: () => listRecentDownloads(days),
    staleTime: 30_000,
  });
}
